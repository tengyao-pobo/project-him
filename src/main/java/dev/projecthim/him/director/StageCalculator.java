package dev.projecthim.him.director;

import dev.projecthim.him.memory.PlayerMemory;

/** Derives a player's stage from play time, encounters and HIM's obsession with them. */
public final class StageCalculator {

    private StageCalculator() {
    }

    public static Stage stageOf(PlayerMemory memory, DirectorSettings settings) {
        if (memory.stageOverride() >= 0) {
            return Stage.of(memory.stageOverride());
        }
        // Obsession lets HIM progress faster with players he is fixated on.
        double minutes = memory.playSeconds() / 60.0 * (1.0 + memory.obsession());
        int notable = memory.notableEncounters();
        Stage result = Stage.UNAWARE;
        for (Stage s : Stage.values()) {
            if (minutes >= settings.stageMinutes()[s.ordinal()] && notable >= settings.stageEncounters()[s.ordinal()]) {
                result = s;
            }
        }
        return result.ordinal() > settings.maxStage().ordinal() ? settings.maxStage() : result;
    }
}
