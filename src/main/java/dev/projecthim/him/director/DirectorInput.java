package dev.projecthim.him.director;

import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;

/**
 * Everything the director needs for one decision. The runtime fills in the availability flags from
 * the encounter coordinator and the performance governor.
 *
 * @param performance        0..1 multiplier from the performance governor (1 = healthy server)
 * @param apparitionAvailable whether another visible HIM may be spawned right now
 * @param huntAvailable       whether a hunt slot is free for this world
 * @param worldEditsAllowed   whether real world modifications are allowed at the player's location
 * @param intervalSeconds     time covered by this evaluation (for the hazard integral)
 */
public record DirectorInput(
        PlayerContext context,
        PlayerMemory memory,
        WorldState world,
        TensionTracker tension,
        Trigger trigger,
        long now,
        double performance,
        boolean apparitionAvailable,
        boolean huntAvailable,
        boolean worldEditsAllowed,
        double intervalSeconds) {
}
