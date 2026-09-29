package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.json.Json;
import com.dankmc.dankvotes.core.json.Json.JsonArray;
import com.dankmc.dankvotes.core.json.Json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-backed persistence for everything DankVotes needs to remember:
 *   - per-player cumulative vote counts (milestones, /votetop)
 *   - per-player streaks (current, best, last vote day)
 *   - offline vote queue (replayed on join)
 *   - vote-party progress (survives restarts)
 *   - server-wide total
 *
 * Writes are DEBOUNCED and ATOMIC: mutations mark the store dirty and a background
 * thread flushes at most every few seconds (write to a temp file, then rename), so a
 * burst of votes never blocks a game thread and a crash mid-write can't corrupt data.
 * {@link #close()} performs a final synchronous flush.
 */
public class VoteStorage {

    /** Per-player record. */
    public static class PlayerData {
        public int votes;
        public int streak;
        public int bestStreak;
        public long lastVoteDay = -1;     // epoch day of last counted vote
        public long lastVoteTime;         // epoch millis
    }

    /** Leaderboard row. */
    public static class TopEntry {
        public final String name;
        public final int votes;
        public TopEntry(String name, int votes) { this.name = name; this.votes = votes; }
    }

    private final File file;
    private final Map<String, PlayerData> players = new ConcurrentHashMap<String, PlayerData>();
    private final Map<String, List<Vote>> offlineQueue = new ConcurrentHashMap<String, List<Vote>>();
    private final AtomicInteger partyProgress = new AtomicInteger(0);
    private final AtomicInteger totalVotes = new AtomicInteger(0);

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService flusher;
    private volatile boolean closed = false;

    public VoteStorage(File dataFolder) {
        this.file = new File(dataFolder, "votes-data.json");
        load();
        this.flusher = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "DankVotes-Storage");
                t.setDaemon(true);
                return t;
            }
        });
        this.flusher.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { flushIfDirty(); }
        }, 3, 3, TimeUnit.SECONDS);
    }

    // ── Player counts / streaks ──────────────────────────────────────

    private PlayerData data(String username) {
        String key = username.toLowerCase();
        PlayerData d = players.get(key);
        if (d == null) {
            d = new PlayerData();
            PlayerData prev = players.putIfAbsent(key, d);
            if (prev != null) d = prev;
        }
        return d;
    }

    /**
     * Count a vote for the player: increments their total, updates their day-streak,
     * and bumps the server-wide total. Returns the player's NEW cumulative count.
     */
    public synchronized int recordVote(String username, long timestampMillis, boolean trackStreak) {
        PlayerData d = data(username);
        d.votes++;
        d.lastVoteTime = Math.max(d.lastVoteTime, timestampMillis);
        if (trackStreak) {
            long today = epochDay(timestampMillis);
            if (d.lastVoteDay == today) {
                // Same day: streak unchanged
            } else if (d.lastVoteDay == today - 1) {
                d.streak++;
            } else {
                d.streak = 1;
            }
            if (d.streak > d.bestStreak) d.bestStreak = d.streak;
            d.lastVoteDay = today;
        }
        totalVotes.incrementAndGet();
        markDirty();
        return d.votes;
    }

    public int getVoteCount(String username) {
        PlayerData d = players.get(username.toLowerCase());
        return d == null ? 0 : d.votes;
    }

    /** Current streak, accounting for a streak that has lapsed (no vote yesterday or today). */
    public int getStreak(String username) {
        PlayerData d = players.get(username.toLowerCase());
        if (d == null) return 0;
        long today = epochDay(System.currentTimeMillis());
        if (d.lastVoteDay < today - 1) return 0; // lapsed
        return d.streak;
    }

    public int getBestStreak(String username) {
        PlayerData d = players.get(username.toLowerCase());
        return d == null ? 0 : d.bestStreak;
    }

    public boolean hasVotedToday(String username) {
        PlayerData d = players.get(username.toLowerCase());
        return d != null && d.lastVoteDay == epochDay(System.currentTimeMillis());
    }

    public long getLastVoteTime(String username) {
        PlayerData d = players.get(username.toLowerCase());
        return d == null ? 0L : d.lastVoteTime;
    }

    public boolean hasData(String username) {
        return players.containsKey(username.toLowerCase());
    }

    public synchronized void setVoteCount(String username, int count) {
        data(username).votes = Math.max(0, count);
        markDirty();
    }

    public synchronized void resetPlayer(String username) {
        players.remove(username.toLowerCase());
        offlineQueue.remove(username.toLowerCase());
        markDirty();
    }

    /** Top voters by cumulative count. */
    public List<TopEntry> getTopVoters(int limit) {
        List<TopEntry> all = new ArrayList<TopEntry>();
        for (Map.Entry<String, PlayerData> e : players.entrySet()) {
            if (e.getValue().votes > 0) all.add(new TopEntry(e.getKey(), e.getValue().votes));
        }
        Collections.sort(all, new Comparator<TopEntry>() {
            @Override public int compare(TopEntry a, TopEntry b) {
                if (b.votes != a.votes) return b.votes - a.votes;
                return a.name.compareTo(b.name);
            }
        });
        return all.size() > limit ? new ArrayList<TopEntry>(all.subList(0, limit)) : all;
    }

    /** 1-based rank of the player on the leaderboard, or 0 if unranked. */
    public int getRank(String username) {
        List<TopEntry> all = getTopVoters(Integer.MAX_VALUE);
        String key = username.toLowerCase();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(key)) return i + 1;
        }
        return 0;
    }

    public int getTotalVotes() { return totalVotes.get(); }
    public int getPlayerCount() { return players.size(); }

    // ── Vote party ───────────────────────────────────────────────────

    public int getPartyProgress() { return partyProgress.get(); }

    public int incrementPartyProgress() {
        int n = partyProgress.incrementAndGet();
        markDirty();
        return n;
    }

    public void resetPartyProgress() {
        partyProgress.set(0);
        markDirty();
    }

    // ── Offline queue ────────────────────────────────────────────────

    public synchronized void queueOfflineVote(Vote vote) {
        String key = vote.getUsername().toLowerCase();
        List<Vote> list = offlineQueue.get(key);
        if (list == null) {
            list = new ArrayList<Vote>();
            offlineQueue.put(key, list);
        }
        list.add(vote);
        markDirty();
    }

    /** Pop all queued votes for a player (called when they join). */
    public synchronized List<Vote> drainOfflineVotes(String username) {
        List<Vote> votes = offlineQueue.remove(username.toLowerCase());
        if (votes != null && !votes.isEmpty()) markDirty();
        return votes == null ? new ArrayList<Vote>() : votes;
    }

    public boolean hasOfflineVotes(String username) {
        List<Vote> v = offlineQueue.get(username.toLowerCase());
        return v != null && !v.isEmpty();
    }

    public int getQueuedVoteCount() {
        int n = 0;
        for (List<Vote> l : offlineQueue.values()) n += l.size();
        return n;
    }

    // ── Persistence ──────────────────────────────────────────────────

    private void markDirty() {
        dirty.set(true);
    }

    private void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    /** Force a synchronous write (used on shutdown and after admin edits). */
    public void flush() {
        dirty.set(false);
        save();
    }

    /** Stop the background flusher and write any pending changes. */
    public void close() {
        if (closed) return;
        closed = true;
        flusher.shutdownNow();
        flush();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (Strings.isBlank(content)) return;
            JsonObject root = Json.parseObject(content);

            // Legacy format (v1.0.0-alpha): "counts": { name: n }
            JsonObject counts = root.optObject("counts");
            if (counts != null) {
                for (String key : counts.keys()) {
                    data(key).votes = counts.optInt(key, 0);
                }
            }

            JsonObject playersObj = root.optObject("players");
            if (playersObj != null) {
                for (String key : playersObj.keys()) {
                    JsonObject p = playersObj.optObject(key);
                    if (p == null) continue;
                    PlayerData d = data(key);
                    d.votes = p.optInt("votes", 0);
                    d.streak = p.optInt("streak", 0);
                    d.bestStreak = p.optInt("bestStreak", 0);
                    d.lastVoteDay = p.optLong("lastVoteDay", -1L);
                    d.lastVoteTime = p.optLong("lastVoteTime", 0L);
                }
            }

            partyProgress.set(root.optInt("partyProgress", 0));
            int total = root.optInt("totalVotes", -1);
            if (total < 0) {
                total = 0;
                for (PlayerData d : players.values()) total += d.votes;
            }
            totalVotes.set(total);

            JsonObject queue = root.optObject("queue");
            if (queue != null) {
                for (String key : queue.keys()) {
                    JsonArray arr = queue.optArray(key);
                    if (arr == null) continue;
                    List<Vote> list = new ArrayList<Vote>();
                    for (int i = 0; i < arr.length(); i++) {
                        JsonObject v = arr.getObject(i);
                        if (v == null) continue;
                        list.add(new Vote(
                            v.optString("username"),
                            v.optString("service"),
                            v.optString("address"),
                            v.optLong("timestamp"),
                            v.optBoolean("verified", true),
                            v.optLong("apiId", 0),
                            v.optInt("voteNumber", 0)
                        ));
                    }
                    offlineQueue.put(key, list);
                }
            }
        } catch (Exception e) {
            // Corrupt file — keep a backup and start fresh rather than crash
            System.err.println("[DankVotes] Could not read votes-data.json (" + e.getMessage()
                + "). A backup will be kept as votes-data.json.corrupt");
            try {
                Files.copy(file.toPath(), new File(file.getParentFile(), "votes-data.json.corrupt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    private synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.put("version", 2);
            root.put("partyProgress", partyProgress.get());
            root.put("totalVotes", totalVotes.get());

            JsonObject playersObj = new JsonObject();
            for (Map.Entry<String, PlayerData> e : players.entrySet()) {
                PlayerData d = e.getValue();
                JsonObject p = new JsonObject();
                p.put("votes", d.votes);
                p.put("streak", d.streak);
                p.put("bestStreak", d.bestStreak);
                p.put("lastVoteDay", d.lastVoteDay);
                p.put("lastVoteTime", d.lastVoteTime);
                playersObj.put(e.getKey(), p);
            }
            root.put("players", playersObj);

            JsonObject queue = new JsonObject();
            for (Map.Entry<String, List<Vote>> e : offlineQueue.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Vote v : e.getValue()) {
                    JsonObject o = new JsonObject();
                    o.put("username", v.getUsername());
                    o.put("service", v.getServiceName());
                    o.put("address", v.getAddress());
                    o.put("timestamp", v.getTimestamp());
                    o.put("verified", v.isVerified());
                    o.put("apiId", v.getApiId());
                    o.put("voteNumber", v.getVoteNumber());
                    arr.put(o);
                }
                queue.put(e.getKey(), arr);
            }
            root.put("queue", queue);

            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            // Atomic write: temp file then rename, so a crash never leaves a half-written file.
            File tmp = new File(file.getParentFile(), "votes-data.json.tmp");
            Files.write(tmp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[DankVotes] Could not save votes-data.json: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    static long epochDay(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
    }
}
