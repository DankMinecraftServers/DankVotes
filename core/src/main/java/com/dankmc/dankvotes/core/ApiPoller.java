package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonArray;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbound polling client. Periodically asks the DankMinecraftServers API for new votes,
 * processes them, then acknowledges so they aren't delivered twice.
 *
 * This is the modern, firewall-friendly mode - NO port forwarding required. The server
 * makes outbound HTTPS calls, which work behind NAT, on shared hosts, etc.
 *
 * Endpoints used:
 *   GET  {base}/api/v1/queue        (Authorization: Bearer {token})
 *   POST {base}/api/v1/acknowledge  body {"ids":[...]}
 *
 * Robustness:
 *   - every vote that is processed (or rejected as junk) is acknowledged, even if a
 *     later vote in the same batch throws, so nothing is ever re-delivered
 *   - repeated failures back off (up to ~5 minutes) and log once instead of every cycle
 */
public class ApiPoller {

    /** Service name reported for votes from the polling API. Matches what the site sends
     *  over Votifier so duplicate detection treats both channels as the same source. */
    public static final String SERVICE_NAME = "DankMinecraftServers";

    private final Platform platform;
    private final DankVotesConfig config;
    private final RewardEngine engine;
    private Platform.TaskHandle handle;

    private volatile long lastSuccessMillis = 0L;
    private volatile long lastErrorMillis = 0L;
    private volatile String lastError = null;
    private volatile int consecutiveFailures = 0;
    private volatile long backoffUntil = 0L;
    private volatile long totalReceived = 0L;

    public ApiPoller(Platform platform, DankVotesConfig config, RewardEngine engine) {
        this.platform = platform;
        this.config = config;
        this.engine = engine;
    }

    public void start() {
        if (!config.pollingEnabled) return;
        if (Strings.isBlank(config.apiToken) || config.apiToken.startsWith("PASTE-")) {
            platform.getLogger().warning("Polling is enabled but polling.api-token is not set. "
                + "Get your token from your server's dashboard on " + config.apiBaseUrl
                + " (Dashboard -> your server -> Vote Rewards) and paste it into config.yml.");
            return;
        }
        int interval = Math.max(5, config.pollIntervalSeconds);
        platform.getLogger().info("Polling " + config.apiBaseUrl + " for votes every " + interval + "s.");
        handle = platform.scheduleRepeatingAsync(new Runnable() {
            @Override public void run() { poll(); }
        }, interval);
    }

    public void stop() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }

    public boolean isRunning() { return handle != null; }
    public long getLastSuccessMillis() { return lastSuccessMillis; }
    public String getLastError() { return lastError; }
    public long getLastErrorMillis() { return lastErrorMillis; }
    public long getTotalReceived() { return totalReceived; }

    /** One poll cycle. Runs on an async thread. */
    public void poll() {
        long now = System.currentTimeMillis();
        if (now < backoffUntil) return;

        List<Long> toAck = new ArrayList<Long>();
        try {
            String body = httpGet(config.apiBaseUrl + "/api/v1/queue");
            if (body == null) return; // already logged / rate-limited

            JsonObject root = Json.parseObject(body);
            JsonArray votes = root.optArray("votes");
            onSuccess();
            if (votes == null || votes.isEmpty()) return;

            for (int i = 0; i < votes.length(); i++) {
                JsonObject v = votes.getObject(i);
                if (v == null) continue;
                long id = v.optLong("id", 0);
                if (id > 0) toAck.add(id); // ack no matter what happens below
                try {
                    String username = v.optString("username", "");
                    long ts = v.optLong("timestamp", System.currentTimeMillis() / 1000L) * 1000L;
                    boolean verified = v.optBoolean("verified", true);
                    if (Strings.isBlank(username)) continue;

                    Vote vote = new Vote(username, SERVICE_NAME, "", ts, verified, id);
                    if (engine.processVote(vote)) totalReceived++;
                } catch (Exception perVote) {
                    platform.getLogger().warning("Failed to process vote id " + id + ": " + perVote.getMessage());
                }
            }
        } catch (Exception e) {
            onFailure("Vote poll failed: " + e.getMessage());
        } finally {
            if (!toAck.isEmpty()) acknowledge(toAck);
        }
    }

    private void acknowledge(List<Long> ids) {
        try {
            JsonObject payload = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Long id : ids) arr.put(id);
            payload.put("ids", arr);
            httpPost(config.apiBaseUrl + "/api/v1/acknowledge", payload.toString());
        } catch (Exception e) {
            // The site will re-send these next poll; the engine's duplicate window
            // (default 120s) prevents a second reward in the meantime.
            platform.getLogger().warning("Vote acknowledge failed (" + e.getMessage()
                + "). Duplicate protection will suppress re-delivery.");
        }
    }

    private void onSuccess() {
        lastSuccessMillis = System.currentTimeMillis();
        if (consecutiveFailures > 0) {
            platform.getLogger().info("Vote polling recovered after " + consecutiveFailures + " failed attempt(s).");
        }
        consecutiveFailures = 0;
        backoffUntil = 0L;
    }

    private void onFailure(String message) {
        consecutiveFailures++;
        lastError = message;
        lastErrorMillis = System.currentTimeMillis();
        // Exponential backoff: 30s, 60s, 120s, 240s, capped at 5 minutes.
        long delay = Math.min(300L, 30L * (1L << Math.min(4, consecutiveFailures - 1)));
        backoffUntil = System.currentTimeMillis() + delay * 1000L;
        if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
            platform.getLogger().warning(message + " (attempt " + consecutiveFailures
                + "; retrying in " + delay + "s)");
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code == 401 || code == 403) {
            onFailure("API rejected the token (HTTP " + code + "). Check polling.api-token in config.yml.");
            return null;
        }
        if (code == 429) {
            // Rate limited - the site allows ~30 polls/minute; just skip this cycle quietly.
            return null;
        }
        if (code != 200) {
            onFailure("API queue returned HTTP " + code);
            return null;
        }
        return readStream(conn);
    }

    private void httpPost(String url, String json) throws Exception {
        HttpURLConnection conn = open(url, "POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        try {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        } finally {
            os.close();
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code);
        }
        // Drain so the connection can be reused.
        readStream(conn);
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + config.apiToken);
        conn.setRequestProperty("User-Agent", "DankVotes/" + platform.getPluginVersion()
            + " (" + platform.getPlatformName() + ")");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        return conn;
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getInputStream();
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }
}
