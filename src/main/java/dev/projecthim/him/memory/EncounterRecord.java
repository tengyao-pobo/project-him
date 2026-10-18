package dev.projecthim.him.memory;

/** A compact summary of one significant encounter. */
public record EncounterRecord(long time, String eventId, String mode, String world, int x, int y, int z,
                              Outcome outcome) {
}
