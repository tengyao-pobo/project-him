package dev.projecthim.him.director;

/**
 * How far HIM's relationship with a player has progressed. New players are not hunted on their
 * first evening; the presence grows over real play time and actual encounters.
 */
public enum Stage {
    /** Only faint, deniable signs. */
    UNAWARE,
    /** Distant sightings start. */
    NOTICED,
    /** He follows and interferes. */
    WATCHED,
    /** He may hunt. */
    HUNTED,
    /** Everything is possible and happens more often. */
    OBSESSED;

    public static Stage of(int ordinal) {
        Stage[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    public double normalized() {
        return ordinal() / (double) (values().length - 1);
    }
}
