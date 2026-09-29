package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases for a newer version once at startup (and every 12 hours while
 * the server runs). Purely informational: it logs a line and exposes the result for
 * admins; it never downloads anything.
 */
public class UpdateChecker {

    private final Platform platform;
    private final DankVotesConfig config;
    private Platform.TaskHandle handle;
    private volatile String latestVersion = null;
    private volatile String downloadUrl = null;

    public UpdateChecker(Platform platform, DankVotesConfig config) {
        this.platform = platform;
        this.config = config;
    }

    public void start() {
        if (!config.updateCheckEnabled || Strings.isBlank(config.updateRepo)) return;
        // First check ~10s after startup, then every 12h.
        platform.scheduleDelayedAsync(new Runnable() {
            @Override public void run() { check(); }
        }, 10);
        handle = platform.scheduleRepeatingAsync(new Runnable() {
            @Override public void run() { check(); }
        }, 12L * 60L * 60L);
    }

    public void stop() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }

    /** Newer version string if one is available, else null. */
    public String getAvailableUpdate() {
        String current = platform.getPluginVersion();
        if (latestVersion == null || isNewer(latestVersion, current)) return latestVersion;
        return null;
    }

    public String getDownloadUrl() { return downloadUrl; }

    public void check() {
        try {
            String url = "https://api.github.com/repos/" + config.updateRepo + "/releases/latest";
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "DankVotes/" + platform.getPluginVersion());
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return;

            InputStream is = conn.getInputStream();
            String body;
            try {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
                body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                is.close();
            }

            JsonObject release = Json.parseObject(body);
            String tag = release.optString("tag_name", "");
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            if (Strings.isBlank(tag)) return;

            latestVersion = tag;
            downloadUrl = release.optString("html_url", "https://github.com/" + config.updateRepo + "/releases");

            String current = platform.getPluginVersion();
            if (isNewer(tag, current)) {
                platform.getLogger().info("A new DankVotes version is available: " + tag
                    + " (you have " + current + "). Download: " + downloadUrl);
            }
        } catch (Exception e) {
            if (config.debug) platform.getLogger().info("Update check skipped: " + e.getMessage());
        }
    }

    /** Loose semantic-version comparison: "1.2.10" > "1.2.9"; non-numeric parts are ignored. */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || current == null) return false;
        int[] a = parts(candidate), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String core = v.split("[-+]")[0];
        String[] s = core.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try { out[i] = Integer.parseInt(s[i].replaceAll("[^0-9]", "")); }
            catch (Exception e) { out[i] = 0; }
        }
        return out;
    }
}
