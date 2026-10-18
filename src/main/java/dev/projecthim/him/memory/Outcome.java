package dev.projecthim.him.memory;

/** How a player reacted to (or how a chase ended for) an encounter. */
public enum Outcome {
    /** The player never noticed anything visible. */
    UNNOTICED,
    /** The player looked at HIM but did nothing notable. */
    NOTICED,
    /** The player walked towards HIM. */
    APPROACHED,
    /** The player ran away. */
    FLED,
    /** The player hit HIM. */
    ATTACKED,
    /** The player escaped a hunt. */
    ESCAPED,
    /** The player was killed during a hunt. */
    CAUGHT,
    /** The player drove HIM off by fighting during a hunt. */
    REPELLED,
    /** The player killed HIM during a hunt. */
    SLAIN,
    /** The player logged out during the encounter. */
    LOGGED_OUT,
    /** The encounter was cancelled for technical reasons (world change, death, performance). */
    INTERRUPTED;

    public boolean isNotable() {
        return this != UNNOTICED && this != INTERRUPTED;
    }
}
