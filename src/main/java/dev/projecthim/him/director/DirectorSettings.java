package dev.projecthim.him.director;

import java.util.Set;

/**
 * Tunables of the director, read from the configuration.
 *
 * @param frequency         global multiplier on how often anything happens (1 = default)
 * @param intensity         global multiplier on the allowed intensity (1 = default)
 * @param aggression        multiplier on the HUNT intent's utility (0 disables hunts)
 * @param meanGapMinutes    mean quiet time between events for each stage (index = stage)
 * @param minGapSeconds     hard minimum quiet time between two events for one player
 * @param weibullShape      shape of the hazard curve; >1 makes events likelier the longer it has been quiet
 * @param stageMinutes      play minutes required for each stage
 * @param stageEncounters   notable encounters required for each stage
 * @param maxStage          highest stage the director may reach (admins can cap the horror)
 * @param disabledEvents    ids of events that must never be chosen
 * @param subversionGapMs   minimum time between two deliberate pattern breaks for one player
 * @param evaluationSeconds how often each player is evaluated
 * @param cooldownMultiplier multiplier on every event's own cooldown
 */
public record DirectorSettings(
        double frequency,
        double intensity,
        double aggression,
        double[] meanGapMinutes,
        double minGapSeconds,
        double weibullShape,
        double[] stageMinutes,
        int[] stageEncounters,
        Stage maxStage,
        Set<String> disabledEvents,
        long subversionGapMs,
        double evaluationSeconds,
        double cooldownMultiplier) {

    public DirectorSettings {
        if (meanGapMinutes.length != Stage.values().length || stageMinutes.length != Stage.values().length
                || stageEncounters.length != Stage.values().length) {
            throw new IllegalArgumentException("stage arrays must have exactly " + Stage.values().length + " entries");
        }
        meanGapMinutes = meanGapMinutes.clone();
        stageMinutes = stageMinutes.clone();
        stageEncounters = stageEncounters.clone();
        disabledEvents = Set.copyOf(disabledEvents);
    }

    public static DirectorSettings defaults() {
        return new DirectorSettings(1.0, 1.0, 1.0,
                new double[] {35, 24, 17, 13, 10},
                150, 2.0,
                new double[] {0, 15, 45, 120, 240},
                new int[] {0, 0, 2, 5, 10},
                Stage.OBSESSED, Set.of(), 45 * 60_000L, 6.0, 1.0);
    }

    public double meanGapMinutes(Stage stage) {
        return meanGapMinutes[stage.ordinal()];
    }
}
