package com.dankmc.dankvotes.core;

/**
 * One effect to play for one player, as the core hands it to the platform: the effect's
 * settings, its title and subtitle with placeholders filled in (still in legacy '&' colour
 * codes), and how long to wait first.
 */
public final class EffectPlan {

    private final String trigger;
    private final DankVotesConfig.Effect effect;
    private final String title;
    private final String subtitle;
    private final int delayTicks;
    private final int rank;
    private final boolean preview;

    public EffectPlan(String trigger, DankVotesConfig.Effect effect, String title, String subtitle, int delayTicks) {
        this(trigger, effect, title, subtitle, delayTicks, 0);
    }

    /** @param rank how big a moment it is: a playing effect isn't cut off by a smaller one */
    public EffectPlan(String trigger, DankVotesConfig.Effect effect, String title, String subtitle, int delayTicks, int rank) {
        this(trigger, effect, title, subtitle, delayTicks, rank, false);
    }

    /**
     * @param rank    how big a moment it is: a playing effect isn't cut off by a smaller one
     * @param preview an admin's preview (/dankvotes effect): it replaces whatever is playing,
     *                then holds only its own rank, so a vote party still takes over from it
     */
    public EffectPlan(String trigger, DankVotesConfig.Effect effect, String title, String subtitle, int delayTicks, int rank,
                      boolean preview) {
        this.trigger = trigger == null ? "" : trigger;
        this.effect = effect;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.delayTicks = Math.max(0, delayTicks);
        this.rank = rank;
        this.preview = preview;
    }

    /** vote, streak, milestone, vote-party or reminder. */
    public String getTrigger() { return trigger; }
    public DankVotesConfig.Effect getEffect() { return effect; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public int getDelayTicks() { return delayTicks; }
    /**
     * How big a moment this is. When it starts, it replaces the effect the player is seeing
     * unless that one ranks higher and is still playing (a vote party isn't cut off by the
     * effect of votes saved for the player, say).
     */
    public int getRank() { return rank; }

    /** Started by /dankvotes effect rather than by a vote. */
    public boolean isPreview() { return preview; }

    /**
     * Whether this may take over from {@code playing}, an effect the player is still seeing:
     * not from a bigger one, unless this is a preview.
     */
    public boolean mayReplace(EffectPlan playing) {
        return playing == null || preview || playing.rank <= rank;
    }

    public boolean hasTitle() {
        return !Strings.isBlank(title) || !Strings.isBlank(subtitle);
    }

    @Override
    public String toString() {
        return "EffectPlan{" + trigger + ", " + effect.animation + "/" + effect.particle + ", sound=" + effect.sound
            + (effect.firework ? ", firework " + effect.fireworkType : "") + (hasTitle() ? ", title='" + title + "'" : "")
            + (delayTicks > 0 ? ", delay=" + delayTicks : "") + (preview ? ", preview" : "") + "}";
    }
}
