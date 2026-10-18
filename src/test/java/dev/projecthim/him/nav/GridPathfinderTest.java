package dev.projecthim.him.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import dev.projecthim.him.nav.GridPathfinder.Cell;
import org.junit.jupiter.api.Test;

class GridPathfinderTest {

    /** A flat stone floor at y=0 (feet at y=1) with editable blocks; beyond |60| is "unloaded". */
    private static final class Grid implements GridPathfinder.Blocks {
        final Map<String, Cell> blocks = new HashMap<>();

        void set(int x, int y, int z, Cell m) {
            blocks.put(x + "," + y + "," + z, m);
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (Math.abs(x) > 60 || Math.abs(z) > 60) {
                return Cell.UNLOADED;
            }
            Cell m = blocks.get(x + "," + y + "," + z);
            if (m != null) {
                return m;
            }
            return y <= 0 ? Cell.SOLID : Cell.AIR;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }

        void wall(int x, int height, int gapZ) {
            for (int z = -60; z <= 60; z++) {
                if (z == gapZ) {
                    continue;
                }
                for (int y = 1; y <= height; y++) {
                    set(x, y, z, Cell.SOLID);
                }
            }
        }
    }

    private static GridPathfinder.Point p(int x, int y, int z) {
        return new GridPathfinder.Point(x, y, z);
    }

    @Test
    void straightLineOnFlatGround() {
        GridPathfinder.Result r = new GridPathfinder(new Grid()).find(p(0, 1, 0), p(10, 1, 0), 3000);
        assertTrue(r.reachesGoal());
        assertEquals(10, r.points().size());
    }

    @Test
    void goesAroundWallThroughGap() {
        Grid g = new Grid();
        g.wall(5, 3, 8);
        GridPathfinder.Result r = new GridPathfinder(g).find(p(0, 1, 0), p(10, 1, 0), 5000);
        assertTrue(r.reachesGoal());
        assertTrue(r.points().stream().anyMatch(pt -> pt.x() == 5 && pt.z() == 8), "must use the gap");
    }

    @Test
    void stepsUpOneBlockButNotTwo() {
        Grid g = new Grid();
        for (int x = 5; x <= 15; x++) {
            for (int z = -60; z <= 60; z++) {
                g.set(x, 1, z, Cell.SOLID);
            }
        }
        assertTrue(new GridPathfinder(g).find(p(0, 1, 0), p(10, 2, 0), 3000).reachesGoal());

        Grid cliff = new Grid();
        for (int x = 5; x <= 15; x++) {
            for (int z = -60; z <= 60; z++) {
                cliff.set(x, 1, z, Cell.SOLID);
                cliff.set(x, 2, z, Cell.SOLID);
            }
        }
        assertFalse(new GridPathfinder(cliff).find(p(0, 1, 0), p(10, 3, 0), 3000).reachesGoal());
    }

    @Test
    void dropsDownLedges() {
        Grid g = new Grid();
        for (int x = -5; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                g.set(x, 1, z, Cell.SOLID);
                g.set(x, 2, z, Cell.SOLID);
            }
        }
        assertTrue(new GridPathfinder(g).find(p(0, 3, 0), p(8, 1, 0), 3000).reachesGoal());
    }

    @Test
    void avoidsLavaAndPassesWoodenDoorsOnly() {
        Grid g = new Grid();
        for (int z = -60; z <= 60; z++) {
            g.set(5, 0, z, Cell.HAZARD);
        }
        g.set(5, 0, 3, Cell.SOLID);
        GridPathfinder.Result r = new GridPathfinder(g).find(p(0, 1, 0), p(10, 1, 0), 5000);
        assertTrue(r.reachesGoal());
        assertTrue(r.points().stream().noneMatch(pt -> pt.x() == 5 && pt.z() != 3), "must not walk over lava");

        Grid d = new Grid();
        d.wall(5, 3, 999);
        d.set(5, 1, 0, Cell.DOOR);
        d.set(5, 2, 0, Cell.DOOR);
        assertTrue(new GridPathfinder(d).find(p(0, 1, 0), p(10, 1, 0), 5000).reachesGoal());
        assertFalse(new GridPathfinder(d, false).find(p(0, 1, 0), p(10, 1, 0), 5000).reachesGoal(),
                "doors are walls when HIM may not open them");
        d.set(5, 1, 0, Cell.TALL);
        d.set(5, 2, 0, Cell.TALL);
        assertFalse(new GridPathfinder(d).find(p(0, 1, 0), p(10, 1, 0), 5000).reachesGoal());
    }

    @Test
    void unreachableGoalGivesBoundedPartialPath() {
        Grid g = new Grid();
        g.wall(5, 4, 999);
        GridPathfinder.Result r = new GridPathfinder(g).find(p(0, 1, 0), p(10, 1, 0), 2000);
        assertFalse(r.reachesGoal());
        assertTrue(r.explored() <= 2000);
        assertTrue(r.points().isEmpty() || r.points().getLast().x() == 4, "ends as close as possible");
    }

    @Test
    void neverEntersUnloadedArea() {
        GridPathfinder.Result r = new GridPathfinder(new Grid()).find(p(55, 1, 0), p(70, 1, 0), 3000);
        assertFalse(r.reachesGoal());
        assertTrue(r.points().stream().allMatch(pt -> Math.abs(pt.x()) <= 60));
    }

    @Test
    void climbsLaddersOntoWalls() {
        Grid g = new Grid();
        g.wall(5, 4, 999);
        GridPathfinder.Point top = p(5, 5, 0);
        assertFalse(new GridPathfinder(g).find(p(0, 1, 0), top, 5000).reachesGoal(), "no way up without a ladder");
        for (int y = 1; y <= 4; y++) {
            g.set(4, y, 0, Cell.CLIMB);
        }
        GridPathfinder.Result r = new GridPathfinder(g).find(p(0, 1, 0), top, 5000);
        assertTrue(r.reachesGoal(), "climbs the ladder and steps onto the wall");
        assertTrue(r.points().stream().anyMatch(pt -> pt.x() == 4 && pt.y() == 4));
    }
}
