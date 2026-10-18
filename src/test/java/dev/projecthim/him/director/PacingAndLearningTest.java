package dev.projecthim.him.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.Personality;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PacingAndLearningTest {

    @Test
    void tensionCyclesThroughPeakAndRelax() {
        SplittableRandom random = new SplittableRandom(1);
        TensionTracker t = new TensionTracker(0, random);
        long now = 0;
        assertEquals(TensionTracker.Phase.BUILD_UP, t.phase());
        for (int i = 0; i < 6; i++) {
            t.onEvent(0.9, now);
        }
        t.update(now, random);
        assertEquals(TensionTracker.Phase.PEAK, t.phase());
        assertTrue(t.intensityCap() < 0.5, "no big events at the peak");
        now += 5 * 60_000;
        t.update(now, random);
        assertEquals(TensionTracker.Phase.RELAX, t.phase());
        assertTrue(t.hazardMultiplier() < 0.2, "relax is (almost) silent");
        now += 30 * 60_000;
        t.update(now, random);
        assertEquals(TensionTracker.Phase.BUILD_UP, t.phase());
    }

    @Test
    void budgetRefillsAndRefunds() {
        GlobalBudget b = new GlobalBudget(2, 1, 0);
        assertTrue(b.tryConsume(2, 0));
        assertFalse(b.tryConsume(0.5, 0));
        assertTrue(b.canAfford(0.9, 60_000));
        b.refund(5);
        assertEquals(2.0, b.tokens(60_000), 1.0E-9);
    }

    @Test
    void stageProgressesWithTimeAndEncounters() {
        DirectorSettings s = DirectorSettings.defaults();
        PlayerMemory m = new PlayerMemory(UUID.randomUUID(), "p", 0);
        assertEquals(Stage.UNAWARE, StageCalculator.stageOf(m, s));
        m.addPlayTime(20 * 60, 0);
        assertEquals(Stage.NOTICED, StageCalculator.stageOf(m, s));
        m.addPlayTime(10 * 3600, 0);
        // Time alone is not enough: later stages need actual encounters.
        assertEquals(Stage.NOTICED, StageCalculator.stageOf(m, s));
        for (int i = 0; i < 12; i++) {
            m.recordEncounter(new dev.projecthim.him.memory.EncounterRecord(i, "x", "PRIVATE", "w", 0, 0, 0, Outcome.NOTICED));
        }
        assertEquals(Stage.OBSESSED, StageCalculator.stageOf(m, s));
        m.setStageOverride(2);
        assertEquals(Stage.WATCHED, StageCalculator.stageOf(m, s));
    }

    @Test
    void learningNudgesInPlausibleDirectionsAndStaysBounded() {
        SplittableRandom random = new SplittableRandom(3);
        WorldState world = WorldState.fresh(random, 0);
        PlayerMemory m = new PlayerMemory(UUID.randomUUID(), "p", 0);
        EventSpec spec = EventSpec.builder("test", Intent.OBSERVE).modes(EncounterMode.PRIVATE).visual().build();
        Personality p = world.personality();
        double boldBefore = p.get(Trait.BOLDNESS);
        Learning.apply(m, world, spec, Outcome.ATTACKED, false, 0);
        assertTrue(p.get(Trait.BOLDNESS) < boldBefore, "being attacked makes him less bold");
        double aggressionBefore = p.get(Trait.AGGRESSION);
        Learning.apply(m, world, spec, Outcome.FLED, false, 0);
        assertTrue(p.get(Trait.AGGRESSION) > aggressionBefore, "fleeing players invite the predator");
        for (int i = 0; i < 10_000; i++) {
            Learning.apply(m, world, spec, Outcome.values()[i % Outcome.values().length], i % 7 == 0, i);
        }
        for (Trait t : Trait.values()) {
            assertTrue(p.get(t) >= Personality.MIN && p.get(t) <= Personality.MAX);
        }
        assertTrue(m.obsession() >= 0 && m.obsession() <= 1);
    }

    @Test
    void personalityRelaxesTowardsBaseline() {
        Personality p = Personality.seeded(new SplittableRandom(9), 1);
        double base = p.baseline(Trait.CURIOSITY);
        p.set(Trait.CURIOSITY, 0.95);
        p.relax(30L * 86_400_000L, 0.1);
        assertTrue(Math.abs(p.get(Trait.CURIOSITY) - base) < Math.abs(0.95 - base) * 0.1);
    }

    @Test
    void expectationsDiscourageRepetitionAndEnableSubversion() {
        PlayerMemory m = new PlayerMemory(UUID.randomUUID(), "p", 0);
        var e = m.expectations();
        double fresh = e.novelty("distant_figure", "OBSERVE");
        e.recordEvent("distant_figure", "OBSERVE");
        e.recordEvent("distant_figure", "OBSERVE");
        assertTrue(e.novelty("distant_figure", "OBSERVE") < fresh * 0.3);
        assertTrue(e.novelty("cover_peek", "OBSERVE") > e.novelty("distant_figure", "OBSERVE"));
        assertEquals(0.0, e.subversionChance(0.9, 10_000_000, 60_000));
        for (int i = 0; i < 6; i++) {
            e.recordPatternConfirmed();
        }
        assertTrue(e.subversionChance(0.9, 10_000_000, 60_000) > 0);
        e.recordSubversion(10_000_000);
        assertEquals(0.0, e.subversionChance(0.9, 10_000_001, 60_000));
    }
}
