package dev.projecthim.him.director;

/** How an encounter is distributed between players. */
public enum EncounterMode {
    /** Only the target perceives anything. Nobody else can see or hear it. */
    PRIVATE,
    /** Several players take part, each receiving different (possibly contradicting) cues. */
    GROUP,
    /** All participants perceive the same HIM in the same place. */
    SHARED
}
