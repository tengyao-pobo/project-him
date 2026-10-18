package dev.projecthim.him.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.event.impl.EventCatalog;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HorrorDirectorTest {

    private static final List<EventSpec> CATALOG = EventCatalog.create().stream().map(HorrorEvent::spec).toList();

    private SplittableRandom random;
    private WorldState world;
    private PlayerMemory memory;
    private HorrorDirector director;
    private GlobalBudget budget;

    @BeforeEach
    void setUp() {
        random = new SplittableRandom(7);
        world = WorldState.fresh(random, 0);
        memory = new PlayerMemory(UUID.randomUUID(), "tester", 0);
        director = new HorrorDirector(CATALOG, DirectorSettings.defaults());
        budget = new GlobalBudget(1000, 1000, 0);
    }

    private static PlayerContext.Builder context() {
        return PlayerContext.builder(UUID.randomUUID(), "world");
    }

    private DirectorInput input(PlayerContext ctx, TensionTracker tension, long now, Trigger trigger) {
        return new DirectorInput(ctx, memory, world, tension, trigger, now, 1.0, true, true, true, 6);
    }

    @Test
    void neverActsInsideMinimumGap() {
        TensionTracker tension = new TensionTracker(0, random);
        long now = (long) (DirectorSettings.defaults().minGapSeconds() * 1000) - 1000;
        assertEquals(0.0, director.hazardProbability(input(context().build(), tension, now, Trigger.PERIODIC), Stage.OBSESSED));
    }

    @Test
    void hazardGrowsWithSilence() {
        TensionTracker tension = new TensionTracker(0, random);
        double previous = 0;
        for (int minutes = 4; minutes <= 60; minutes += 4) {
            double p = director.hazardProbability(input(context().build(), tension, minutes * 60_000L, Trigger.PERIODIC),
                    Stage.WATCHED);
            assertTrue(p >= previous, "hazard must not decrease with longer silence");
            previous = p;
        }
        assertTrue(previous > 0);
    }

    @Test
    void stageGatesEventsAndHunts() {
        memory.setStageOverride(0);
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput in = input(context().night(true).underground(true).build(), tension, 3_600_000, Trigger.PERIODIC);
        for (EventSpec s : director.candidates(in, Stage.UNAWARE, 1.0)) {
            assertEquals(Stage.UNAWARE, s.minStage(), s.id() + " must not be available at stage UNAWARE");
            assertTrue(s.intent() != Intent.HUNT);
        }
    }

    @Test
    void huntRequiresSlotAndAggression() {
        TensionTracker tension = new TensionTracker(0, random);
        PlayerContext ctx = context().night(true).build();
        DirectorInput noSlot = new DirectorInput(ctx, memory, world, tension, Trigger.PERIODIC, 3_600_000, 1.0, true,
                false, true, 6);
        assertTrue(director.candidates(noSlot, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.intent() == Intent.HUNT));

        DirectorSettings d = DirectorSettings.defaults();
        HorrorDirector peaceful = new HorrorDirector(CATALOG, new DirectorSettings(d.frequency(), d.intensity(), 0,
                d.meanGapMinutes(), d.minGapSeconds(), d.weibullShape(), d.stageMinutes(), d.stageEncounters(),
                d.maxStage(), d.disabledEvents(), d.subversionGapMs(), d.evaluationSeconds(), d.cooldownMultiplier()));
        DirectorInput slot = input(ctx, tension, 3_600_000, Trigger.PERIODIC);
        assertTrue(peaceful.candidates(slot, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.intent() == Intent.HUNT));
        assertTrue(director.candidates(slot, Stage.OBSESSED, 1.0).stream().anyMatch(s -> s.intent() == Intent.HUNT));
    }

    @Test
    void cooldownsAndDisabledEventsAreRespected() {
        TensionTracker tension = new TensionTracker(0, random);
        PlayerContext ctx = context().night(true).build();
        long now = 10 * 3_600_000L;
        memory.markEventTime("distant_figure", now - 60_000);
        DirectorInput in = input(ctx, tension, now, Trigger.PERIODIC);
        assertTrue(director.candidates(in, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.id().equals("distant_figure")));

        DirectorSettings d = DirectorSettings.defaults();
        HorrorDirector restricted = new HorrorDirector(CATALOG, new DirectorSettings(d.frequency(), d.intensity(),
                d.aggression(), d.meanGapMinutes(), d.minGapSeconds(), d.weibullShape(), d.stageMinutes(),
                d.stageEncounters(), d.maxStage(), Set.of("hunt"), d.subversionGapMs(), d.evaluationSeconds(),
                d.cooldownMultiplier()));
        assertTrue(restricted.candidates(in, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.id().equals("hunt")));
    }

    @Test
    void manualOnlyEventsAreNeverCandidates() {
        TensionTracker tension = new TensionTracker(0, random);
        for (Trigger t : Trigger.values()) {
            if (t == Trigger.MANUAL) {
                continue;
            }
            DirectorInput in = input(context().build(), tension, 3_600_000, t);
            assertTrue(director.candidates(in, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.id().equals("summon")));
        }
    }

    @Test
    void groupOnlyEventsNeedOtherPlayers() {
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput alone = input(context().night(true).build(), tension, 3_600_000, Trigger.PERIODIC);
        assertTrue(director.candidates(alone, Stage.OBSESSED, 1.0).stream().noneMatch(s -> s.id().equals("behind_companion")));
        DirectorInput group = input(context().night(true).nearbyPlayers(2).build(), tension, 3_600_000, Trigger.PERIODIC);
        assertTrue(director.candidates(group, Stage.OBSESSED, 1.0).stream().anyMatch(s -> s.id().equals("behind_companion")));
    }

    @Test
    void sharedOnlyEventsAreAvailableToLonePlayers() {
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput alone = input(context().underground(true).skyVisible(false).build(), tension, 3_600_000,
                Trigger.PERIODIC);
        assertTrue(director.candidates(alone, Stage.OBSESSED, 1.0).stream().anyMatch(s -> s.id().equals("tunnel")));
    }

    @Test
    void busyOrStrugglingServerHolds() {
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput busy = input(context().inCombat(true).build(), tension, 3_600_000, Trigger.PERIODIC);
        assertInstanceOf(DirectorDecision.Hold.class, director.decide(busy, budget, random));
        DirectorInput critical = new DirectorInput(context().build(), memory, world, tension, Trigger.PERIODIC, 3_600_000,
                0.0, true, true, true, 6);
        assertInstanceOf(DirectorDecision.Hold.class, director.decide(critical, budget, random));
    }

    @Test
    void globalBudgetLimitsActivity() {
        GlobalBudget empty = new GlobalBudget(1, 0.0001, 0);
        empty.tryConsume(1, 0);
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput in = new DirectorInput(context().night(true).build(), memory, world, tension, Trigger.PERIODIC,
                10 * 3_600_000L, 1.0, true, true, true, 3_600);
        memory.setStageOverride(4);
        for (int i = 0; i < 50; i++) {
            DirectorDecision d = director.decide(in, empty, random);
            assertInstanceOf(DirectorDecision.Hold.class, d);
        }
    }

    /**
     * Simulates many hours of one player with the real catalog and checks the pacing: there are
     * events, but not too many, gaps respect the minimum, and the selection is varied rather than a
     * loop.
     */
    @Test
    void longRunPacingIsVariedAndBounded() {
        memory.setStageOverride(4);
        long now = 0;
        TensionTracker tension = new TensionTracker(now, random);
        GlobalBudget realBudget = new GlobalBudget(4, 0.6, now);
        int hours = 24;
        long end = hours * 3_600_000L;
        List<Long> times = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> intents = new HashSet<>();
        String previous = null;
        int repeats = 0;
        while (now < end) {
            now += 6_000;
            boolean night = (now / 600_000) % 2 == 1;
            PlayerContext ctx = context().night(night).underground((now / 1_800_000) % 3 == 0)
                    .sheltered((now / 900_000) % 4 == 0).light(night ? 4 : 14).speed(2).build();
            DirectorInput in = new DirectorInput(ctx, memory, world, tension, Trigger.PERIODIC, now, 1.0, true, true,
                    true, 6);
            if (director.decide(in, realBudget, random) instanceof DirectorDecision.Act act) {
                times.add(now);
                counts.merge(act.spec().id(), 1, Integer::sum);
                intents.add(act.spec().intent().name());
                tension.onEvent(act.spec().intensity(), now);
                memory.markEventTime(act.spec().id(), now);
                memory.expectations().recordEvent(act.spec().id(), act.spec().intent().name());
                if (act.spec().id().equals(previous)) {
                    repeats++;
                }
                previous = act.spec().id();
            }
        }
        double perHour = times.size() / (double) hours;
        assertTrue(perHour >= 1.5 && perHour <= 12, "events per hour out of range: " + perHour);
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) - times.get(i - 1) >= DirectorSettings.defaults().minGapSeconds() * 1000,
                    "gap below minimum");
        }
        assertTrue(counts.size() >= 10, "too little variety: " + counts);
        assertTrue(intents.size() >= 4, "too few intents: " + intents);
        int hunts = counts.getOrDefault("hunt", 0);
        assertTrue(hunts >= 1 && hunts <= hours, "hunts should be rare climaxes, got " + hunts);
        assertTrue(repeats <= times.size() / 6, "too many immediate repeats: " + repeats + " of " + times.size());
        // Gaps must not be a fixed schedule.
        Set<Long> distinctGaps = new HashSet<>();
        for (int i = 1; i < times.size(); i++) {
            distinctGaps.add((times.get(i) - times.get(i - 1)) / 60_000);
        }
        assertTrue(distinctGaps.size() >= Math.min(8, times.size() / 3), "gaps look scheduled: " + distinctGaps);
    }

    @Test
    void reactiveTriggersOnlyPickMatchingEvents() {
        memory.setStageOverride(4);
        TensionTracker tension = new TensionTracker(0, random);
        DirectorInput wake = input(context().night(true).sleeping(false).build(), tension, 3_600_000, Trigger.WAKE);
        for (EventSpec s : director.candidates(wake, Stage.OBSESSED, 1.0)) {
            assertTrue(s.triggers().contains(Trigger.WAKE));
        }
        assertFalse(director.candidates(wake, Stage.OBSESSED, 1.0).isEmpty());
    }
}
