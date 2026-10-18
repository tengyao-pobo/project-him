package dev.projecthim.him.chase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.util.Vec;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ChaseBrainTest {

    private static final ChaseSettings S = ChaseSettings.defaults();

    private static ChaseBrain.Inputs in(long tick, Vec him, Vec target, boolean himSees, boolean targetSees) {
        return new ChaseBrain.Inputs(tick, him, target, Vec.ZERO, himSees, targetSees, false, false, true, true, 0, false, false, false);
    }

    @Test
    void revealsThenPursues() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(1), true, 1.0);
        ChaseBrain.Command first = b.tick(in(0, new Vec(0, 0, 0), new Vec(10, 0, 0), true, true));
        assertEquals(ChaseBrain.Mode.REVEAL, first.mode());
        assertEquals(0, first.speed());
        ChaseBrain.Command later = null;
        for (long t = 1; t <= S.revealTicks() + 2; t++) {
            later = b.tick(in(t, new Vec(0, 0, 0), new Vec(10, 0, 0), true, true));
        }
        assertNotNull(later);
        assertTrue(later.mode() == ChaseBrain.Mode.PURSUE || later.mode() == ChaseBrain.Mode.BURST);
        assertTrue(later.speed() > 0);
    }

    @Test
    void approachSwitchesToRevealWhenSeen() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(1), false, 1.0);
        assertEquals(ChaseBrain.Mode.APPROACH, b.tick(in(0, new Vec(0, 0, 0), new Vec(30, 0, 0), false, false)).mode());
        assertEquals(ChaseBrain.Mode.REVEAL, b.tick(in(1, new Vec(0, 0, 0), new Vec(30, 0, 0), true, true)).mode());
    }

    @Test
    void speedNeverExceedsBurstSpeed() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(5), true, 1.05);
        double max = 0;
        for (long t = 0; t < 2000; t++) {
            ChaseBrain.Command c = b.tick(in(t, new Vec(0, 0, 0), new Vec(8, 0, 0), true, t % 3 == 0));
            if (c.end() != null) {
                break;
            }
            max = Math.max(max, c.speed());
        }
        assertTrue(max <= S.burstSpeed() * 1.05 + 1.0E-9, "speed cap violated: " + max);
    }

    @Test
    void attackOnlyInRange() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(2), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 5; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(1.5, 0, 0), true, true));
        }
        ChaseBrain.Command near = b.tick(in(100, new Vec(0, 0, 0), new Vec(1.5, 0, 0), true, true));
        assertTrue(near.attackAllowed());
        ChaseBrain.Command far = b.tick(in(101, new Vec(0, 0, 0), new Vec(6, 0, 0), true, true));
        assertFalse(far.attackAllowed());
    }

    @Test
    void playerEscapesByDistance() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(3), true, 1.0);
        b.tick(in(0, new Vec(0, 0, 0), new Vec(10, 0, 0), true, true));
        ChaseBrain.Command c = null;
        for (long t = 1; t < 400 && (c == null || c.end() == null); t++) {
            c = b.tick(in(t, new Vec(0, 0, 0), new Vec(S.escapeDistance() + 10, 0, 0), false, false));
        }
        assertNotNull(c);
        assertEquals(ChaseBrain.EndReason.OUTRAN, c.end());
        assertTrue(c.end().playerWon());
    }

    @Test
    void lostSightLeadsToSearchThenGivesUp() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(4), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 5; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(12, 0, 0), true, true));
        }
        long t = 100;
        ChaseBrain.Command c;
        boolean searched = false;
        do {
            c = b.tick(in(t++, new Vec(0, 0, 0), new Vec(20, 0, 0), false, false));
            searched |= c.mode() == ChaseBrain.Mode.SEARCH;
        } while (c.end() == null && t < 5000);
        assertTrue(searched);
        assertEquals(ChaseBrain.EndReason.LOST_SIGHT, c.end());
    }

    @Test
    void enoughHitsRepelHim() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(6), true, 1.0);
        b.tick(in(0, new Vec(0, 0, 0), new Vec(2, 0, 0), true, true));
        ChaseBrain.Command stagger = b.tick(new ChaseBrain.Inputs(1, new Vec(0, 0, 0), new Vec(2, 0, 0), Vec.ZERO,
                true, true, false, false, true, true, 1, false, false, false));
        assertEquals(ChaseBrain.Mode.STAGGER, stagger.mode());
        ChaseBrain.Command end = b.tick(new ChaseBrain.Inputs(2, new Vec(0, 0, 0), new Vec(2, 0, 0), Vec.ZERO, true,
                true, false, false, true, true, S.hitsToRepel(), false, false, false));
        assertEquals(ChaseBrain.EndReason.REPELLED, end.end());
    }

    @Test
    void huntsAlwaysTimeOut() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(7), true, 1.0);
        ChaseBrain.Command c = null;
        for (long t = 0; t <= S.maxDurationTicks() + 2; t++) {
            c = b.tick(in(t, new Vec(0, 0, 0), new Vec(3, 0, 0), true, true));
            if (c.end() != null) {
                break;
            }
        }
        assertNotNull(c);
        assertNotNull(c.end());
    }

    @Test
    void stuckRequestsHiddenRepositionOnlyWhenUnseenThenGivesUp() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(8), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 2; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(10, 0, 0), true, true));
        }
        boolean requestedWhileSeen = false;
        boolean requested = false;
        ChaseBrain.Command c = null;
        for (long t = 100; t < 400; t++) {
            boolean seen = t < 200;
            c = b.tick(new ChaseBrain.Inputs(t, new Vec(0, 0, 0), new Vec(10, 0, 0), Vec.ZERO, true, seen, false, false,
                    false, true, 0, false, false, false));
            if (c.end() != null) {
                break;
            }
            if (c.reposition()) {
                requested = true;
                requestedWhileSeen |= seen;
            }
        }
        assertFalse(requestedWhileSeen, "must never reposition while the player watches");
        assertTrue(requested);
        assertNotNull(c);
        assertEquals(ChaseBrain.EndReason.STUCK, c.end());
    }

    @Test
    void fakeRetreatHidesAndOnlyReappearsUnseen() {
        ChaseSettings always = new ChaseSettings(S.walkSpeed(), S.runSpeed(), S.burstSpeed(), S.burstTicks(), 100_000,
                S.attackRange(), S.attackDamage(), S.attackCooldownTicks(), S.lethal(), 20_000, 0, 10_000, S.searchTicks(),
                S.escapeDistance(), S.hitsToRepel(), 120.0, S.maxRepositions(), S.openDoors());
        ChaseBrain b = new ChaseBrain(always, new SplittableRandom(9), true, 1.0);
        long t = 0;
        ChaseBrain.Command c;
        do {
            c = b.tick(in(t++, new Vec(0, 0, 0), new Vec(15, 0, 0), true, true));
        } while (c.mode() != ChaseBrain.Mode.FAKE_RETREAT && t < 5000);
        assertEquals(ChaseBrain.Mode.FAKE_RETREAT, c.mode());
        boolean hidden = false;
        for (int i = 0; i < 400; i++) {
            c = b.tick(in(t++, new Vec(0, 0, 0), new Vec(15, 0, 0), true, true));
            if (c.mode() == ChaseBrain.Mode.FAKE_RETREAT && !c.bodyVisible()) {
                hidden = true;
            }
            if (c.end() != null) {
                break;
            }
            // While the player keeps watching the carrier's position he must not reappear there.
            if (hidden) {
                assertTrue(c.mode() == ChaseBrain.Mode.FAKE_RETREAT, "reappeared in plain sight");
            }
        }
        assertTrue(hidden);
    }

    private static ChaseBrain.Inputs unreachable(long tick, Vec target, boolean sheltered) {
        return new ChaseBrain.Inputs(tick, new Vec(0, 0, 0), target, Vec.ZERO, true, true, false, sheltered, false,
                false, 0, false, false, false);
    }

    @Test
    void unreachablePlayerIsBesiegedNotAbandoned() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(10), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 2; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(1, 4, 0), true, true));
        }
        ChaseBrain.Command c = null;
        // Standing on a pillar right above him, no path: he must not end as STUCK, he lays siege.
        for (long t = 100; t < 600; t++) {
            c = b.tick(unreachable(t, new Vec(1, 4, 0), false));
            assertNull(c.end(), "a besieged player must not make him give up early");
        }
        assertEquals(ChaseBrain.Mode.SIEGE, c.mode());
        assertTrue(c.breach(), "siege allows breaking or building towards the player");
        // Once a path exists again he resumes the chase.
        for (long t = 600; t < 620; t++) {
            c = b.tick(in(t, new Vec(0, 0, 0), new Vec(1, 4, 0), true, true));
        }
        assertTrue(c.mode() == ChaseBrain.Mode.PURSUE || c.mode() == ChaseBrain.Mode.BURST
                || c.mode() == ChaseBrain.Mode.SEARCH);
    }

    @Test
    void realShelterEndsASiege() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(11), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 2; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(3, 0, 0), true, true));
        }
        ChaseBrain.Command c = null;
        for (long t = 100; t < 600 && (c == null || c.end() == null); t++) {
            c = b.tick(unreachable(t, new Vec(3, 0, 0), true));
        }
        assertNotNull(c);
        assertEquals(ChaseBrain.EndReason.SHELTERED, c.end());
    }

    @Test
    void siegeNeedsTheTargetToBeClose() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(12), true, 1.0);
        for (long t = 0; t < S.revealTicks() + 2; t++) {
            b.tick(in(t, new Vec(0, 0, 0), new Vec(20, 0, 0), true, true));
        }
        for (long t = 100; t < 200; t++) {
            ChaseBrain.Command c = b.tick(unreachable(t, new Vec(20, 0, 0), false));
            assertTrue(c.mode() != ChaseBrain.Mode.SIEGE, "far away targets are chased, not besieged");
        }
    }

    private static ChaseBrain.Inputs flying(long tick, Vec target, boolean flying) {
        return new ChaseBrain.Inputs(tick, new Vec(0, 0, 0), target, Vec.ZERO, false, false, false, false, true,
                true, 0, false, false, flying);
    }

    @Test
    void flyingAwayIsNotAnInstantEscapeAndHeWaitsWhereTheyLand() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(10), true, 1.0);
        b.tick(in(0, new Vec(0, 0, 0), new Vec(5, 0, 0), true, true));
        ChaseBrain.Command c = null;
        // Far away and out of sight in the air for 10 seconds: no OUTRAN, no LOST_SIGHT.
        for (long t = 1; t <= 200; t++) {
            c = b.tick(flying(t, new Vec(5 + t, 20, 0), true));
            assertNull(c.end(), "ended at " + t + " with " + c.end());
        }
        assertEquals(ChaseBrain.Mode.AIRBORNE, c.mode());
        assertFalse(c.bodyVisible(), "hidden while the player is in the air");
        // Landing: one LANDING command, then the hunt goes on.
        boolean landed = false;
        for (long t = 201; t <= 230; t++) {
            c = b.tick(flying(t, new Vec(205, 0, 0), false));
            if (c.mode() == ChaseBrain.Mode.LANDING) {
                landed = true;
                break;
            }
        }
        assertTrue(landed);
        assertEquals(ChaseBrain.Mode.PURSUE, b.mode());
    }

    @Test
    void longFlightIsARealEscapeAndHopsAddUp() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(11), true, 1.0);
        b.setFlightEscapeTicks(300);
        b.tick(in(0, new Vec(0, 0, 0), new Vec(5, 0, 0), true, true));
        ChaseBrain.Command c = null;
        long t = 1;
        // Hop: 200 ticks up, land, take off again: flight time keeps adding up.
        for (; t <= 200; t++) {
            c = b.tick(flying(t, new Vec(10, 20, 0), true));
        }
        for (; t <= 225; t++) {
            c = b.tick(flying(t, new Vec(10, 0, 0), false));
        }
        for (; t <= 600 && c.end() == null; t++) {
            c = b.tick(flying(t, new Vec(10, 20, 0), true));
        }
        assertEquals(ChaseBrain.EndReason.FLEW, c.end());
        assertTrue(t < 360, "ended at " + t);
        assertTrue(c.end().playerWon());
    }

    @Test
    void aShortGlideChangesNothing() {
        ChaseBrain b = new ChaseBrain(S, new SplittableRandom(12), true, 1.0);
        b.tick(in(0, new Vec(0, 0, 0), new Vec(5, 0, 0), true, true));
        for (long t = 1; t <= 5; t++) {
            assertNotEquals(ChaseBrain.Mode.AIRBORNE, b.tick(flying(t, new Vec(5, 1, 0), true)).mode());
        }
    }
}
