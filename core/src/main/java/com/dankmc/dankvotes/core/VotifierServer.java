package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Inbound Votifier protocol server supporting BOTH protocol versions on one port,
 * exactly like NuVotifier - so DankVotes is a true drop-in replacement:
 *
 *  v2 (token / HMAC-SHA256), framed exactly like NuVotifier:
 *   1. On connect, server sends:  "VOTIFIER 2 <challenge>\n"
 *   2. Client sends the magic short 0x733A, a short with the message length, then the message:
 *      a JSON envelope { "payload": "<json>", "signature": "<base64 HMAC>" }
 *      where payload = { username, serviceName, address, timestamp, challenge }
 *   3. Server verifies the HMAC using the shared token and the challenge, then replies
 *      {"status":"ok"} (or {"status":"error","cause":...,"error":...}) and "\r\n".
 *   A bare JSON envelope without the 0x733A header is accepted too.
 *
 *  v1 (RSA):
 *   1. Client ignores the greeting and sends a single 256-byte RSA-encrypted block.
 *   2. Server decrypts with its private key; the plaintext is
 *      "VOTE\n<serviceName>\n<username>\n<address>\n<timestamp>\n".
 *   Keys live in <data>/rsa/public.key and private.key, stored in the same Base64
 *   format as classic Votifier/NuVotifier, so an existing rsa/ folder can simply be
 *   copied across and vote sites keep working with the public key they already have.
 *
 * Protocol detection, as in NuVotifier: the first two bytes are 0x733A for v2; a leading '{'
 * is a bare v2 envelope; anything else is the start of a v1 RSA block.
 *
 * DankVotes' own vote forwarding (proxy -> backend) uses the same v2 format and adds two
 * signed fields to the payload: "verified" (the site's VPN check) and "dankvotesForwarded"
 * (so a forwarded vote is never forwarded again). Other Votifier servers ignore them.
 * Runs on its own daemon thread; each connection is handled on the platform's async pool.
 */
public class VotifierServer implements Runnable {

    private static final int MAX_V2_MESSAGE = 8192;
    private static final int V1_BLOCK = 256;
    private static final int V2_MAGIC = 0x733A;

    private final Platform platform;
    private final DankVotesConfig config;
    private final RewardEngine engine;
    private final SecureRandom random = new SecureRandom();

    /** At most this many connections are handled at once; more are closed straight away. */
    private static final int MAX_CONNECTIONS = 32;
    /** A whole exchange must finish within this time, however slowly the client drips bytes. */
    private static final long CONNECTION_DEADLINE_MS = 10000L;

    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    /** Ids of forwarded votes already handled, so a retried delivery is not rewarded twice. */
    private final Map<String, Boolean> seenForwardIds = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 4096; }
    });

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
                if (!slots.tryAcquire()) {
                    // Flooded: drop the connection rather than queue unbounded work.
                    try { socket.close(); } catch (Exception ignored) {}
                    continue;
                }
                platform.runAsync(new Runnable() {
                    @Override public void run() {
                        try {
                            handleConnection(socket);
                        } finally {
                            slots.release();
                        }
                    }
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

            DataInputStream in = new DataInputStream(new BufferedInputStream(
                new DeadlineInputStream(socket, System.currentTimeMillis() + CONNECTION_DEADLINE_MS)));
            int b0 = in.read();
            if (b0 == -1) {
                socket.close();
                return;
            }
            if (b0 == '{' && jsonFollows(in)) {
                // Bare JSON envelope (no 0x733A header), terminated by a newline or end of stream.
                // (A v1 RSA block can start with '{' too; jsonFollows tells them apart.)
                handleV2(readBareEnvelope(in, (byte) b0), out, challenge);
            } else {
                int b1 = in.read();
                if (b1 == -1) {
                    socket.close();
                    return;
                }
                if (((b0 << 8) | b1) == V2_MAGIC) {
                    int length = in.readUnsignedShort();
                    if (length <= 0 || length > MAX_V2_MESSAGE) {
                        writeError(out, "CorruptedFrameException", "Bad message length " + length);
                    } else {
                        byte[] message = new byte[length];
                        in.readFully(message);
                        handleV2(new String(message, StandardCharsets.UTF_8), out, challenge);
                    }
                } else {
                    handleV1(in, out, (byte) b0, (byte) b1);
                }
            }
            socket.close();
        } catch (Exception e) {
            if (config.debug) platform.getLogger().warning("Votifier connection error: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /** After a '{': is the next non-blank byte a '"', as in a JSON object? Leaves the stream unmoved. */
    private static boolean jsonFollows(InputStream in) throws Exception {
        in.mark(64);
        try {
            for (int i = 0; i < 32; i++) {
                int b = in.read();
                if (b == -1) return false;
                if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
                return b == '"';
            }
            return false;
        } finally {
            in.reset();
        }
    }

    /** Bounds the whole read side of a connection by one deadline (slow-drip protection). */
    private static final class DeadlineInputStream extends FilterInputStream {
        private final Socket socket;
        private final long deadline;

        DeadlineInputStream(Socket socket, long deadline) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
            this.deadline = deadline;
        }

        private void arm() throws IOException {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("vote took too long to arrive");
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
        }

        @Override public int read() throws IOException { arm(); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { arm(); return super.read(b, off, len); }
    }

    private static String readBareEnvelope(InputStream in, byte first) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(first);
        int depth = 0;
        boolean inString = false, escaped = false;
        int b = first;
        while (true) {
            // Track braces so a client that keeps the socket open still gets an answer.
            if (inString) {
                if (escaped) escaped = false;
                else if (b == '\\') escaped = true;
                else if (b == '"') inString = false;
            } else if (b == '"') {
                inString = true;
            } else if (b == '{') {
                depth++;
            } else if (b == '}') {
                depth--;
                if (depth == 0) break;
            }
            b = in.read();
            if (b == -1 || b == '\n') break;
            buf.write(b);
            if (buf.size() > MAX_V2_MESSAGE) throw new IllegalStateException("payload too large");
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    // ── v2 ───────────────────────────────────────────────────────────

    private void handleV2(String message, OutputStream out, String challenge) throws Exception {
        if (Strings.isBlank(config.votifierToken)) {
            writeError(out, "UnsupportedOperationException", "Votifier v2 is not enabled on this server (no token set)");
            return;
        }
        JsonObject envelope;
        JsonObject vote;
        try {
            envelope = Json.parseObject(message);
            vote = Json.parseObject(envelope.optString("payload", ""));
        } catch (Exception e) {
            writeError(out, "CorruptedFrameException", "Malformed vote message");
            return;
        }
        String payload = envelope.optString("payload", "");
        String signature = envelope.optString("signature", "");

        if (!challenge.equals(vote.optString("challenge"))) {
            writeError(out, "CorruptedFrameException", "Challenge is not valid");
            return;
        }
        if (!verifySignature(payload, signature)) {
            platform.getLogger().warning("Votifier v2: signature mismatch - the sender's token does not match votifier.token.");
            writeError(out, "CorruptedFrameException", "Signature is not valid (invalid token?)");
            return;
        }

        String username = vote.optString("username", "");
        String service = vote.optString("serviceName", "unknown");
        String address = vote.optString("address", "");
        long ts = parseTimestamp(vote.get("timestamp"));
        // Signed extras from a forwarding DankVotes proxy; plain Votifier senders don't set them.
        boolean verified = vote.optBoolean("verified", true);
        boolean forwarded = vote.optBoolean("dankvotesForwarded", false);
        String forwardId = vote.optString("dankvotesId", "");

        if (!forwardId.isEmpty() && seenForwardIds.containsKey(forwardId)) {
            // A proxy re-sent a vote we already handled (its first "ok" got lost): don't reward twice.
            if (config.debug) platform.getLogger().info("[debug] Votifier: repeat of forwarded vote " + forwardId + " ignored.");
        } else {
            RewardEngine.Outcome outcome = accept(new Vote(username, service, address, ts, verified, 0, 0, forwarded));
            if (outcome == RewardEngine.Outcome.STOPPING) {
                writeError(out, "ServerStoppingException", "DankVotes is restarting - send the vote again shortly");
                return;
            }
            if (!forwardId.isEmpty()) seenForwardIds.put(forwardId, Boolean.TRUE);
        }

        out.write("{\"status\":\"ok\"}\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ── v1 ───────────────────────────────────────────────────────────

    private void handleV1(InputStream in, OutputStream out, byte first, byte second) throws Exception {
        if (rsaKeys == null) {
            // Not a v2 message and v1 is off - nothing we can do with it.
            if (config.debug) platform.getLogger().warning("Votifier: received a v1 (RSA) vote but votifier.v1-rsa is off.");
            return;
        }
        byte[] block = new byte[V1_BLOCK];
        block[0] = first;
        block[1] = second;
        int read = 2;
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

        if (accept(new Vote(username, service, address, ts, true, 0)) == RewardEngine.Outcome.STOPPING) {
            platform.getLogger().warning("Votifier v1: a vote for " + username + " arrived while DankVotes was stopping and was not processed.");
        }
        // v1 has no response; the client closes after sending.
    }

    private RewardEngine.Outcome accept(Vote vote) {
        RewardEngine.Outcome outcome = engine.processVoteDetailed(vote);
        if (outcome == RewardEngine.Outcome.ACCEPTED) totalReceived++;
        return outcome;
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

    /** NuVotifier's error shape: both fields are read by standard v2 clients. */
    private void writeError(OutputStream out, String cause, String error) {
        try {
            JsonObject err = new JsonObject();
            err.put("status", "error");
            err.put("cause", cause);
            err.put("error", error);
            out.write((err.toString() + "\r\n").getBytes(StandardCharsets.UTF_8));
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
