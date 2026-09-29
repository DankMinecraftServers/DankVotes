package com.dankmc.dankvotes.core;

/**
 * Represents a single vote, regardless of how it arrived (Votifier v1/v2 protocol,
 * NuVotifier hook, or the DankMinecraftServers polling API).
 *
 * Immutable and safe to pass between threads.
 */
public class Vote {
    private final String username;
    private final String serviceName;   // e.g. "DankMinecraftServers"
    private final String address;       // voter address/IP if provided
    private final long timestamp;        // epoch millis
    private final boolean verified;      // true if vote came from a non-VPN/proxy IP
    private final long apiId;            // vote id from the polling API (0 if from Votifier)

    /**
     * The player's cumulative vote count AT THE TIME this vote was counted (1-based).
     * 0 means "not yet counted". This is recorded when a vote is queued for an offline
     * player so that milestone rewards are evaluated against the correct count when the
     * queue is replayed on join (rather than the count after all queued votes).
     */
    private final int voteNumber;

    /** True when another DankVotes (usually a proxy) forwarded this vote here; never forwarded again. */
    private final boolean forwarded;

    public Vote(String username, String serviceName, String address, long timestamp, boolean verified, long apiId) {
        this(username, serviceName, address, timestamp, verified, apiId, 0, false);
    }

    public Vote(String username, String serviceName, String address, long timestamp, boolean verified, long apiId, int voteNumber) {
        this(username, serviceName, address, timestamp, verified, apiId, voteNumber, false);
    }

    public Vote(String username, String serviceName, String address, long timestamp, boolean verified, long apiId,
                int voteNumber, boolean forwarded) {
        this.username = username == null ? "" : username.trim();
        this.serviceName = serviceName == null ? "" : serviceName;
        this.address = address == null ? "" : address;
        this.timestamp = timestamp;
        this.verified = verified;
        this.apiId = apiId;
        this.voteNumber = voteNumber;
        this.forwarded = forwarded;
    }

    /** Copy of this vote with the vote-number snapshot set. */
    public Vote withVoteNumber(int number) {
        return new Vote(username, serviceName, address, timestamp, verified, apiId, number, forwarded);
    }

    public String getUsername()    { return username; }
    public String getServiceName() { return serviceName; }
    public String getAddress()     { return address; }
    public long getTimestamp()     { return timestamp; }
    public boolean isVerified()    { return verified; }
    public long getApiId()         { return apiId; }
    public int getVoteNumber()     { return voteNumber; }
    public boolean isForwarded()   { return forwarded; }

    @Override
    public String toString() {
        return "Vote{user=" + username + ", service=" + serviceName + ", verified=" + verified
            + ", apiId=" + apiId + ", n=" + voteNumber + (forwarded ? ", forwarded" : "") + "}";
    }
}
