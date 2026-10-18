package dev.projecthim.him.director;

/** Why the director is being asked for a decision. */
public enum Trigger {
    /** Regular evaluation tick. */
    PERIODIC,
    /** The player just woke up / left a bed. */
    WAKE,
    /** The player just broke a block. */
    MINE,
    /** The player just joined the server. */
    JOIN,
    /** The player is near a place where something happened before. */
    LANDMARK,
    /** Started by an administrator. The director never uses this trigger. */
    MANUAL;

    /** Base chance that a reactive trigger leads to an attempt at all (before budgets and cooldowns). */
    public double baseChance() {
        return switch (this) {
            case PERIODIC -> 1.0;
            case WAKE -> 0.22;
            case MINE -> 0.012;
            case JOIN -> 0.35;
            case LANDMARK -> 0.25;
            case MANUAL -> 0.0;
        };
    }
}
