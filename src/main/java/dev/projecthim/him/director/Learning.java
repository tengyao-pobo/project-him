package dev.projecthim.him.director;

import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.Personality;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;

/**
 * How HIM changes after an encounter. Small, bounded nudges: a single encounter never flips his
 * character, but a server full of players who always run will slowly produce a bolder hunter, and
 * players who fight back teach him to be careful and patient.
 */
public final class Learning {

    private Learning() {
    }

    public static void apply(PlayerMemory memory, WorldState world, EventSpec spec, Outcome outcome, boolean subverted,
                             long now) {
        Personality p = world.personality();
        memory.expectations().recordEvent(spec.id(), spec.intent().name());
        if (spec.visual() && spec.intent() != Intent.HUNT) {
            memory.behavior().recordReaction(outcome);
            if (subverted) {
                memory.expectations().recordSubversion(now);
            } else if (outcome == Outcome.NOTICED || outcome == Outcome.APPROACHED) {
                memory.expectations().recordPatternConfirmed();
            }
        }
        if (outcome.isNotable()) {
            world.countEncounter();
        }
        switch (outcome) {
            case UNNOTICED -> {
                // Unseen: next time he comes a bit closer.
                p.nudge(Trait.BOLDNESS, 0.012);
                world.adjustAffinity(spec.id(), -0.01);
            }
            case NOTICED -> {
                p.nudge(Trait.CURIOSITY, 0.006);
                memory.adjustObsession(0.015);
                world.adjustAffinity(spec.id(), 0.02);
            }
            case APPROACHED -> {
                p.nudge(Trait.CURIOSITY, 0.01);
                p.nudge(Trait.SUBVERSION, 0.008);
                memory.adjustObsession(0.025);
                world.adjustAffinity(spec.id(), 0.03);
            }
            case FLED -> {
                p.nudge(Trait.AGGRESSION, 0.01);
                p.nudge(Trait.BOLDNESS, 0.008);
                memory.adjustObsession(0.03);
                world.adjustAffinity(spec.id(), 0.04);
            }
            case ATTACKED -> {
                p.nudge(Trait.BOLDNESS, -0.02);
                p.nudge(Trait.PATIENCE, 0.012);
                p.nudge(Trait.AGGRESSION, 0.012);
                memory.adjustObsession(0.04);
            }
            case ESCAPED -> {
                p.nudge(Trait.PATIENCE, 0.015);
                p.nudge(Trait.AGGRESSION, 0.008);
                p.nudge(Trait.SUBVERSION, 0.01);
                memory.adjustObsession(0.05);
            }
            case CAUGHT -> {
                p.nudge(Trait.AGGRESSION, -0.01);
                memory.adjustObsession(-0.06);
            }
            case REPELLED -> {
                p.nudge(Trait.BOLDNESS, -0.025);
                p.nudge(Trait.PATIENCE, 0.02);
                p.nudge(Trait.ISOLATION, 0.015);
                memory.adjustObsession(0.06);
            }
            case LOGGED_OUT -> {
                p.nudge(Trait.PATIENCE, 0.01);
                memory.adjustObsession(0.04);
            }
            case INTERRUPTED -> {
                // Nothing learned.
            }
        }
    }
}
