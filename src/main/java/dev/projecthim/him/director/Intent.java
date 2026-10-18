package dev.projecthim.him.director;

/** What HIM wants to achieve with the next encounter. Each event belongs to exactly one intent. */
public enum Intent {
    /** Make the player feel something is there without showing anything (sounds, light, silence). */
    PRESENCE,
    /** Let himself be seen, briefly and deliberately. */
    OBSERVE,
    /** Follow the player over time, mostly unseen. */
    STALK,
    /** Touch the player's world: their base, their light, their signs. */
    INTERFERE,
    /** Hunt the player. */
    HUNT
}
