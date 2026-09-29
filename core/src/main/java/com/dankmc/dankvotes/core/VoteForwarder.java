package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonArray;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Forwards votes to backend servers over the Votifier v2 protocol, framed exactly like
 * NuVotifier (so the backend can run DankVotes or NuVotifier).
 *
 * This is how a proxy (Velocity, BungeeCord) that receives the votes gets them to the servers
 * behind it - Paper, Folia, Purpur, Sponge... - which then give the in-world rewards.
 *
 * Delivery is reliable and in order: every (vote, server) pair is a job that is retried, with
 * backoff per server, until that server accepts it. The queue is saved to
 * forwarding-queue.json after every change, survives restarts, and a job is dropped only after
 * 7 days. Each vote carries a signed id, so a DankVotes backend ignores a repeat delivery
 * (for example when its "ok" was lost on the way back). In "current" mode a vote goes only to
 * the server the player is on; while they are offline it waits here until they join one.
 */
public final class VoteForwarder {

    static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int TIMEOUT_MS = 5000;
    private static final int V2_MAGIC = 0x733A;
    /** Serialises writes of the queue file across forwarder instances (reloads). */
    private static final Object FILE_LOCK = new Object();

    private final Platform platform;
    private final DankVotesConfig config;
    private final File queueFile;
    private final Map<String, DankVotesConfig.ForwardTarget> targets = new LinkedHashMap<String, DankVotesConfig.ForwardTarget>();
    private final Map<String, TargetState> states = new LinkedHashMap<String, TargetState>();
    private final List<Job> jobs = new ArrayList<Job>();                 // guarded by this
    private final ReentrantLock flushLock = new ReentrantLock();
    private final AtomicReference<Socket> inFlight = new AtomicReference<Socket>();
    private volatile boolean rerun;
    private volatile boolean running;
    private volatile boolean currentMode;
    private Platform.TaskHandle retryTask;

    /** One vote waiting for one server (target null = waiting for the player to join a server). */
    static final class Job {
        String target;
        final Vote vote;
        final String id;
        final long created;
        int attempts;

        Job(String target, Vote vote, String id, long created) {
            this.target = target;
            this.vote = vote;
            this.id = id;
            this.created = created;
        }
    }

    /** Per-server delivery state: backoff and what /dankvotes status shows. */
    static final class TargetState {
        volatile long lastSuccess;
        volatile String lastError;
        volatile boolean failing;
        volatile int failures;
        volatile long retryAt;
    }

    public VoteForwarder(Platform platform, DankVotesConfig config) {
        this.platform = platform;
        this.config = config;
        this.queueFile = new File(platform.getDataFolder(), "forwarding-queue.json");
        for (DankVotesConfig.ForwardTarget t : config.forwardingServers) {
            String key = key(t.name);
            if (targets.containsKey(key)) {
                platform.getLogger().warning("forwarding.servers lists '" + t.name + "' twice - using the first one.");
                continue;
            }
            targets.put(key, t);
            states.put(key, new TargetState());
        }
    }

    public boolean isRunning() { return running; }

    public void start() {
        if (!config.forwardingEnabled) return;
        if (targets.isEmpty()) {
            platform.getLogger().warning("forwarding.enabled is true but forwarding.servers is empty - not forwarding votes.");
            return;
        }
        for (DankVotesConfig.ForwardTarget t : targets.values()) {
            if (Strings.isBlank(t.token)) {
                platform.getLogger().warning("forwarding: server '" + t.name + "' has no token. Use the votifier.token from that "
                    + "server's DankVotes (or NuVotifier) config, or it will refuse the votes.");
            }
        }
        String mode = config.forwardingMode == null ? "all" : config.forwardingMode;
        if (!mode.equals("all") && !mode.equals("current")) {
            platform.getLogger().warning("forwarding.mode '" + mode + "' is not 'all' or 'current' - using 'all'.");
        }
        currentMode = "current".equals(mode);
        if (currentMode && !platform.isProxy()) {
            platform.getLogger().warning("forwarding.mode 'current' needs a proxy (Velocity or BungeeCord) - forwarding to all servers instead.");
            currentMode = false;
        }
        load();
        running = true;
        retryTask = platform.scheduleRepeatingAsync(new Runnable() {
            @Override public void run() { flush(); }
        }, 5);
        int queued = pendingCount();
        platform.getLogger().info("Forwarding votes to " + targets.size() + " server(s): " + names()
            + (currentMode ? " (only the server the player is on)" : " (every server)")
            + (queued > 0 ? " - " + queued + " queued from before the restart." : "."));
        if (queued > 0) triggerFlush();
    }

    /**
     * Stop delivering: cut any exchange in progress short, wait for the delivery round to end,
     * then save the queue. Whatever wasn't confirmed stays queued for the next start.
     */
    public void stop() {
        if (!running) return;
        running = false;
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
        Socket s = inFlight.get();
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        boolean locked = false;
        try {
            locked = flushLock.tryLock(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            save();
        } finally {
            if (locked) flushLock.unlock();
        }
    }

    /** Called by the reward engine for every accepted vote. */
    public void forward(Vote vote) {
        if (!running || vote.isForwarded()) return;
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        synchronized (this) {
            if (currentMode) {
                String server = platform.getPlayerServer(vote.getUsername());
                String target = server == null ? null : targetFor(server);
                jobs.add(new Job(target, vote, id, now));
                if (target == null && config.debug) {
                    platform.getLogger().info("[debug] Holding the vote for " + vote.getUsername()
                        + (server == null ? " until they join a server." : " - '" + server + "' isn't in forwarding.servers."));
                }
            } else {
                for (DankVotesConfig.ForwardTarget t : targets.values()) jobs.add(new Job(t.name, vote, id, now));
            }
        }
        save();
        triggerFlush();
    }

    /** Proxies call this whenever a player connects to a backend server (used by "current" mode). */
    public void onPlayerServer(String username, String serverName) {
        if (!running || !currentMode || username == null || serverName == null) return;
        String target = targetFor(serverName);
        if (target == null) return;
        if (assignHeld(username, target)) {
            save();
            triggerFlush();
        }
    }

    /** Votes waiting to be delivered (including ones held for offline players). */
    public synchronized int pendingCount() {
        return jobs.size();
    }

    /** Lines for /dankvotes status. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<String>();
        if (!running) return out;
        out.add("&7Forwarding: &f" + targets.size() + " server(s) &8| &7mode: &f" + (currentMode ? "current" : "all")
            + " &8| &7queued: &f" + pendingCount());
        for (Map.Entry<String, DankVotesConfig.ForwardTarget> e : targets.entrySet()) {
            TargetState st = states.get(e.getKey());
            DankVotesConfig.ForwardTarget t = e.getValue();
            String state = st.failing
                ? "&cfailing &7(" + (st.lastError == null ? "?" : st.lastError) + ")"
                : (st.lastSuccess > 0 ? "&aok &7(last vote " + CommandText.duration(System.currentTimeMillis() - st.lastSuccess) + " ago)" : "&7no votes yet");
            out.add("&8 - &f" + t.name + " &7" + t.host + ":" + t.port + " " + state);
        }
        return out;
    }

    // ── delivery ─────────────────────────────────────────────────────

    private void triggerFlush() {
        rerun = true;
        platform.runAsync(new Runnable() {
            @Override public void run() { flush(); }
        });
    }

    void flush() {
        if (!running) return;
        if (!flushLock.tryLock()) {
            rerun = true;
            return;
        }
        try {
            do {
                rerun = false;
                flushOnce();
            } while (rerun && running);
        } finally {
            flushLock.unlock();
        }
    }

    private void flushOnce() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        // Held votes (current mode): the player may have reached a listed server since.
        if (currentMode) {
            List<String> heldFor = new ArrayList<String>();
            synchronized (this) {
                for (Job j : jobs) if (j.target == null && !heldFor.contains(j.vote.getUsername())) heldFor.add(j.vote.getUsername());
            }
            for (String name : heldFor) {
                String server = platform.getPlayerServer(name);
                String target = server == null ? null : targetFor(server);
                if (target != null && assignHeld(name, target)) changed = true;
            }
        }

        List<Job> due = new ArrayList<Job>();
        synchronized (this) {
            Iterator<Job> it = jobs.iterator();
            while (it.hasNext()) {
                Job j = it.next();
                if (now - j.created > MAX_AGE_MS) {
                    platform.getLogger().warning("Dropped a vote for " + j.vote.getUsername() + " that could not be forwarded"
                        + (j.target == null ? " (the player never joined a listed server)" : " to '" + j.target + "'") + " for 7 days.");
                    it.remove();
                    changed = true;
                } else if (j.target != null && targets.containsKey(key(j.target))) {
                    due.add(j);
                }
                // Jobs for a server no longer in the config wait (see load()) until they expire.
            }
        }
        if (changed) save();

        for (Job j : due) {
            if (!running) break;
            String key = key(j.target);
            DankVotesConfig.ForwardTarget t = targets.get(key);
            TargetState st = states.get(key);
            if (System.currentTimeMillis() < st.retryAt) continue;   // that server is backing off
            try {
                send(t, j.vote, j.id, inFlight);
                synchronized (this) { jobs.remove(j); }
                save();                                               // a crash can't re-send what was delivered
                st.lastSuccess = System.currentTimeMillis();
                st.failures = 0;
                st.retryAt = 0;
                if (st.failing) {
                    st.failing = false;
                    platform.getLogger().info("Vote forwarding to '" + t.name + "' works again.");
                }
                if (config.debug) platform.getLogger().info("[debug] Forwarded " + j.vote.getUsername() + "'s vote to " + t.name);
            } catch (Exception e) {
                if (!running) break;                                  // stop() closed the socket: keep the job
                j.attempts++;
                st.failures++;
                st.retryAt = System.currentTimeMillis() + backoff(st.failures);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                st.lastError = msg;
                if (!st.failing) {
                    st.failing = true;
                    platform.getLogger().warning("Could not forward a vote to '" + t.name + "' (" + t.host + ":" + t.port + "): "
                        + msg + ". DankVotes keeps it and retries until that server accepts it.");
                }
            }
        }
    }

    private synchronized boolean assignHeld(String username, String target) {
        boolean moved = false;
        for (Job j : jobs) {
            if (j.target == null && j.vote.getUsername().equalsIgnoreCase(username)) {
                j.target = target;
                moved = true;
            }
        }
        return moved;
    }

    /** Wait between attempts at one server: 5s, 10s, 20s ... up to 5 minutes. */
    static long backoff(int failures) {
        long ms = 5000L << Math.min(6, Math.max(0, failures - 1));
        return Math.min(300000L, ms);
    }

    /** One Votifier v2 exchange. Throws with a readable reason when the server refuses. */
    static void send(DankVotesConfig.ForwardTarget t, Vote v) throws Exception {
        send(t, v, null, null);
    }

    static void send(DankVotesConfig.ForwardTarget t, Vote v, String id, AtomicReference<Socket> inFlight) throws Exception {
        Socket socket = new Socket();
        if (inFlight != null) inFlight.set(socket);
        try {
            socket.connect(new InetSocketAddress(t.host, t.port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String greeting = readLine(in, 512).trim();
            String[] parts = greeting.split(" ");
            if (parts.length < 3 || !"VOTIFIER".equals(parts[0]) || !parts[1].startsWith("2")) {
                throw new IOException(greeting.isEmpty() ? "no Votifier greeting (is votifier.enabled on?)"
                    : "not a Votifier v2 server (greeting '" + greeting + "')");
            }
            JsonObject payload = new JsonObject();
            payload.put("serviceName", v.getServiceName());
            payload.put("username", v.getUsername());
            payload.put("address", v.getAddress());
            payload.put("timestamp", v.getTimestamp());
            payload.put("challenge", parts[2]);
            payload.put("verified", v.isVerified());
            payload.put("dankvotesForwarded", true);
            if (id != null) payload.put("dankvotesId", id);
            String payloadText = payload.toString();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(t.token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(payloadText.getBytes(StandardCharsets.UTF_8)));

            JsonObject message = new JsonObject();
            message.put("payload", payloadText);
            message.put("signature", signature);
            byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(V2_MAGIC);
            out.writeShort(body.length);
            out.write(body);
            out.flush();

            String response = readLine(in, 4096).trim();
            if (response.isEmpty()) throw new IOException("no answer from the server");
            JsonObject r = Json.parseObject(response);
            if (!"ok".equals(r.optString("status"))) {
                String error = r.optString("error", "");
                throw new IOException("refused: " + r.optString("cause", "error") + (error.isEmpty() ? "" : " - " + error));
            }
        } finally {
            if (inFlight != null) inFlight.compareAndSet(socket, null);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            buf.write(b);
            if (buf.size() > max) break;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── persistence ──────────────────────────────────────────────────

    private void load() {
        if (!queueFile.exists()) return;
        int loaded = 0;
        try {
            JsonObject root = Json.parseObject(new String(Files.readAllBytes(queueFile.toPath()), StandardCharsets.UTF_8));
            JsonArray arr = root.optArray("jobs");
            if (arr == null) return;
            synchronized (this) {
                for (int i = 0; i < arr.length(); i++) {
                    JsonObject o = arr.getObject(i);
                    if (o == null) continue;
                    JsonObject v = o.optObject("vote");
                    if (v == null) continue;
                    Vote vote = new Vote(v.optString("username"), v.optString("service"), v.optString("address"),
                        v.optLong("timestamp"), v.optBoolean("verified", true), v.optLong("apiId", 0));
                    String target = o.optString("target", "");
                    String id = o.optString("id", "");
                    if (id.isEmpty()) id = UUID.randomUUID().toString();
                    long created = o.optLong("created", System.currentTimeMillis());
                    if (target.isEmpty() && !currentMode) {
                        // Held for "current" mode, but the proxy now forwards to every server.
                        for (DankVotesConfig.ForwardTarget t : targets.values()) jobs.add(withAttempts(new Job(t.name, vote, id, created), o));
                    } else {
                        jobs.add(withAttempts(new Job(target.isEmpty() ? null : target, vote, id, created), o));
                    }
                    loaded++;
                }
                Map<String, Integer> orphans = new LinkedHashMap<String, Integer>();
                for (Job j : jobs) {
                    if (j.target != null && !targets.containsKey(key(j.target))) {
                        Integer n = orphans.get(j.target);
                        orphans.put(j.target, n == null ? 1 : n + 1);
                    }
                }
                for (Map.Entry<String, Integer> e : orphans.entrySet()) {
                    platform.getLogger().warning(e.getValue() + " queued vote(s) are for server '" + e.getKey() + "', which is no "
                        + "longer in forwarding.servers. They're kept for 7 days in case you add it back under that name.");
                }
            }
        } catch (Exception e) {
            File backup = new File(queueFile.getParentFile(), "forwarding-queue.json.corrupt");
            try {
                Files.copy(queueFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
            platform.getLogger().warning("Could not read forwarding-queue.json (" + e.getMessage() + "). A copy was kept as "
                + backup.getName() + "; " + loaded + " queued vote(s) were recovered.");
        }
    }

    private static Job withAttempts(Job j, JsonObject o) {
        j.attempts = o.optInt("attempts", 0);
        return j;
    }

    /** Write the queue atomically; the snapshot is taken under the same lock as the write. */
    private void save() {
        synchronized (FILE_LOCK) {
            JsonObject root = new JsonObject();
            root.put("version", 1);
            JsonArray arr = new JsonArray();
            synchronized (this) {
                for (Job j : jobs) {
                    JsonObject v = new JsonObject();
                    v.put("username", j.vote.getUsername());
                    v.put("service", j.vote.getServiceName());
                    v.put("address", j.vote.getAddress());
                    v.put("timestamp", j.vote.getTimestamp());
                    v.put("verified", j.vote.isVerified());
                    v.put("apiId", j.vote.getApiId());
                    JsonObject o = new JsonObject();
                    o.put("target", j.target == null ? "" : j.target);
                    o.put("id", j.id);
                    o.put("created", j.created);
                    o.put("attempts", j.attempts);
                    o.put("vote", v);
                    arr.put(o);
                }
            }
            root.put("jobs", arr);
            try {
                File dir = queueFile.getParentFile();
                if (!dir.exists()) dir.mkdirs();
                File tmp = File.createTempFile("forwarding-queue", ".tmp", dir);
                try {
                    Files.write(tmp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
                    try {
                        Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicUnsupported) {
                        Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    if (tmp.exists()) tmp.delete();
                }
            } catch (IOException e) {
                platform.getLogger().warning("Could not save forwarding-queue.json: " + e.getMessage());
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String targetFor(String serverName) {
        DankVotesConfig.ForwardTarget t = targets.get(key(serverName));
        return t == null ? null : t.name;
    }

    private String names() {
        StringBuilder sb = new StringBuilder();
        for (DankVotesConfig.ForwardTarget t : targets.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name);
        }
        return sb.toString();
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
