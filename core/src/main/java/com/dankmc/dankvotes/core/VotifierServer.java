package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Inbound Votifier protocol server supporting BOTH protocol versions on one port,
 * exactly like NuVotifier - so DankVotes is a true drop-in replacement:
 *
 *  v2 (token / HMAC-SHA256):
 *   1. On connect, server sends:  "VOTIFIER 2 <challenge>\n"
 *   2. Client sends a JSON envelope: { "payload": "<json>", "signature": "<base64 HMAC>" }
 *      where payload = { username, serviceName, address, timestamp, challenge }
 *   3. Server verifies the HMAC using the shared token and the challenge, then replies
 *      { "status": "ok" }.
 *
 *  v1 (RSA):
 *   1. Client ignores the greeting and sends a single 256-byte RSA-encrypted block.
 *   2. Server decrypts with its private key; the plaintext is
 *      "VOTE\n<serviceName>\n<username>\n<address>\n<timestamp>\n".
 *   Keys live in <data>/rsa/public.key and private.key, stored in the same Base64
 *   format as classic Votifier/NuVotifier, so an existing rsa/ folder can simply be
 *   copied across and vote sites keep working with the public key they already have.
 *
 * Protocol detection: the first byte received is '{' for v2, anything else is v1.
 * Runs on its own daemon thread; each connection is handled on the platform's async pool.
 */
public class VotifierServer implements Runnable {

    private static final int MAX_V2_LINE = 8192;
    private static final int V1_BLOCK = 256;

    private final Platform platform;
    private final DankVotesConfig config;
    private final RewardEngine engine;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread thread;
    private KeyPair rsaKeys;
    private volatile long totalReceived = 0L;

    public VotifierServer(Platform platform, DankVotesConfig config, RewardEngine engine) {
        this.platform = platform;
        this.config = config;
        this.engine = engine;
    }

    public void start() {
        if (!config.votifierEnabled) return;
        boolean v2 = !Strings.isBlank(config.votifierToken);
        if (config.votifierV1Enabled) {
            rsaKeys = loadOrCreateKeys(new File(platform.getDataFolder(), "rsa"));
        }
        if (!v2 && rsaKeys == null) {
            platform.getLogger().warning("Votifier is enabled but no v2 token is set and v1 RSA is disabled - not starting listener.");
            return;
        }
        if (!v2) {
            platform.getLogger().info("Votifier: no v2 token set - accepting v1 (RSA) votes only. "
                + "Set votifier.token to also accept v2 (token) votes.");
        }
        running = true;
        thread = new Thread(this, "DankVotes-Votifier");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
    }

    public boolean isRunning() { return running && serverSocket != null && !serverSocket.isClosed(); }
    public long getTotalReceived() { return totalReceived; }

    /** Base64 public key to paste into vote sites that use the v1 protocol (null if v1 off). */
    public String getPublicKeyBase64() {
        return rsaKeys == null ? null : Base64.getEncoder().encodeToString(rsaKeys.getPublic().getEncoded());
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.votifierHost, config.votifierPort));
            platform.getLogger().info("Votifier listener bound to " + config.votifierHost + ":" + config.votifierPort
                + " (v2" + (rsaKeys != null ? " + v1" : "") + ")");
        } catch (Exception e) {
            platform.getLogger().severe("Could not bind Votifier port " + config.votifierPort + ": " + e.getMessage()
                + " - is another plugin (e.g. NuVotifier) already using it?");
            running = false;
            return;
        }

        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                platform.runAsync(new Runnable() {
                    @Override public void run() { handleConnection(socket); }
                });
            } catch (Exception e) {
                if (running) {
                    platform.getLogger().warning("Votifier accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            String challenge = newChallenge();

            OutputStream out = socket.getOutputStream();
            out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            int first = in.read();
            if (first == -1) {
                socket.close();
                return;
            }

            if (first == '{') {
                handleV2(in, out, (byte) first, challenge);
            } else {
                handleV1(in, out, (byte) first);
            }
            socket.close();
        } catch (Exception e) {
            if (config.debug) platform.getLogger().warning("Votifier connection error: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── v2 ───────────────────────────────────────────────────────────

    private void handleV2(InputStream in, OutputStream out, byte first, String challenge) throws Exception {
        if (Strings.isBlank(config.votifierToken)) {
            writeError(out, "v2 not enabled on this server");
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(first);
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            buf.write(b);
            if (buf.size() > MAX_V2_LINE) {
                writeError(out, "payload too large");
                return;
            }
        }
        String line = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();

        JsonObject envelope = Json.parseObject(line);
        String payload = envelope.optString("payload", "");
        String signature = envelope.optString("signature", "");

        if (!verifySignature(payload, signature)) {
            platform.getLogger().warning("Votifier v2: signature mismatch - the vote site's token does not match votifier.token.");
            writeError(out, "signature mismatch");
            return;
        }

        JsonObject vote = Json.parseObject(payload);
        if (!challenge.equals(vote.optString("challenge"))) {
            writeError(out, "challenge mismatch");
            return;
        }

        String username = vote.optString("username", "");
        String service = vote.optString("serviceName", "unknown");
        String address = vote.optString("address", "");
        long ts = parseTimestamp(vote.get("timestamp"));

        accept(new Vote(username, service, address, ts, true, 0));

        JsonObject ok = new JsonObject();
        ok.put("status", "ok");
        out.write(ok.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ── v1 ───────────────────────────────────────────────────────────

    private void handleV1(InputStream in, OutputStream out, byte first) throws Exception {
        if (rsaKeys == null) {
            // Not a JSON payload and v1 is off - nothing we can do with it.
            writeError(out, "v1 not enabled on this server");
            return;
        }
        byte[] block = new byte[V1_BLOCK];
        block[0] = first;
        int read = 1;
        while (read < V1_BLOCK) {
            int n = in.read(block, read, V1_BLOCK - read);
            if (n == -1) break;
            read += n;
        }
        if (read != V1_BLOCK) {
            if (config.debug) platform.getLogger().warning("Votifier v1: short block (" + read + " bytes)");
            return;
        }

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, rsaKeys.getPrivate());
        String plain;
        try {
            plain = new String(cipher.doFinal(block), StandardCharsets.UTF_8);
        } catch (Exception e) {
            platform.getLogger().warning("Votifier v1: could not decrypt vote - the vote site is using a different public key. "
                + "Give it the key from plugins/DankVotes/rsa/public.key (or run /dankvotes key).");
            return;
        }

        String[] parts = plain.split("\n");
        if (parts.length < 4 || !"VOTE".equals(parts[0].trim())) {
            platform.getLogger().warning("Votifier v1: malformed vote payload.");
            return;
        }
        String service = parts[1].trim();
        String username = parts[2].trim();
        String address = parts[3].trim();
        long ts = parts.length > 4 ? parseTimestamp(parts[4].trim()) : System.currentTimeMillis();

        accept(new Vote(username, service, address, ts, true, 0));
        // v1 has no response; the client closes after sending.
    }

    private void accept(Vote vote) {
        if (engine.processVote(vote)) totalReceived++;
    }

    // ── crypto helpers ───────────────────────────────────────────────

    private boolean verifySignature(String payload, String signatureB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.votifierToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] provided = Base64.getDecoder().decode(signatureB64);
            return MessageDigest.isEqual(computed, provided);
        } catch (Exception e) {
            return false;
        }
    }

    private String newChallenge() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Load rsa/public.key + rsa/private.key (classic Votifier Base64 format) or generate
     * a new 2048-bit pair. Returns null if the keys can't be loaded or created.
     */
    private KeyPair loadOrCreateKeys(File dir) {
        File pub = new File(dir, "public.key");
        File priv = new File(dir, "private.key");
        try {
            if (pub.exists() && priv.exists()) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                byte[] pubBytes = Base64.getMimeDecoder().decode(readKeyFile(pub));
                byte[] privBytes = Base64.getMimeDecoder().decode(readKeyFile(priv));
                PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
                PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                platform.getLogger().info("Loaded Votifier v1 RSA keys from " + dir.getName() + "/");
                return new KeyPair(publicKey, privateKey);
            }
            if (!dir.exists()) dir.mkdirs();
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Files.write(pub.toPath(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()).getBytes(StandardCharsets.UTF_8));
            Files.write(priv.toPath(), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
            platform.getLogger().info("Generated new Votifier v1 RSA keys in " + dir.getName()
                + "/. Give vote sites the contents of public.key.");
            return pair;
        } catch (Exception e) {
            platform.getLogger().severe("Could not load or create Votifier RSA keys: " + e.getMessage());
            return null;
        }
    }

    private static String readKeyFile(File f) throws Exception {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        // Tolerate PEM headers/footers and whitespace.
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("-----")) continue;
            sb.append(t);
        }
        return sb.toString();
    }

    private void writeError(OutputStream out, String reason) {
        try {
            JsonObject err = new JsonObject();
            err.put("status", "error");
            err.put("cause", reason);
            out.write(err.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {}
    }

    private long parseTimestamp(Object ts) {
        try {
            long v = (ts instanceof Number) ? ((Number) ts).longValue() : Long.parseLong(String.valueOf(ts).trim());
            // Some sites send seconds, most send millis.
            return v < 100000000000L ? v * 1000L : v;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
