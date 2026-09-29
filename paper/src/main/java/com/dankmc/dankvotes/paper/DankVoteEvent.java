package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.Vote;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired whenever DankVotes receives a vote, BEFORE it is counted or rewarded.
 * Other plugins can listen to react (crates, stats, Discord bots...) or cancel it.
 *
 * <pre>{@code
 * @EventHandler
 * public void onVote(DankVoteEvent e) {
 *     String player = e.getUsername();
 *     String site   = e.getServiceName();
 * }
 * }</pre>
 *
 * This event may be fired asynchronously (votes arrive on network threads); check
 * {@link #isAsynchronous()} before touching the world and schedule to the main/region
 * thread if needed.
 */
public class DankVoteEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vote vote;
    private boolean cancelled;

    public DankVoteEvent(Vote vote) {
        super(!Bukkit.isPrimaryThread());
        this.vote = vote;
    }

    public Vote getVote() { return vote; }
    public String getUsername() { return vote.getUsername(); }
    public String getServiceName() { return vote.getServiceName(); }
    public String getAddress() { return vote.getAddress(); }
    public long getTimestamp() { return vote.getTimestamp(); }
    /** True when the vote site confirmed the voter's IP was not a VPN/proxy (polling API only). */
    public boolean isVerified() { return vote.isVerified(); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
