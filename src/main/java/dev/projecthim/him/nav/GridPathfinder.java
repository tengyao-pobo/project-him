package dev.projecthim.him.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * A* over the block grid for a player-sized body (1 wide, 2 tall).
 *
 * <p>Moves: 8 horizontal directions (diagonals only without cutting corners), stepping up one
 * block, dropping up to three blocks, swimming in water (slowly, also up and down), and walking
 * through wooden doors and fence gates (the hunt opens them). Lava, fire, cactus, magma, powder
 * snow, berry bushes and cobwebs are avoided. Unloaded chunks are walls, so a search never loads a
 * chunk. Each search is bounded by a node budget; if the goal cannot be reached within it, the path
 * to the explored node closest to the goal is returned, like vanilla navigation does.
 */
public final class GridPathfinder {

    public static final int DEFAULT_MAX_NODES = 3000;
    private static final int MAX_DROP = 3;
    private static final double SQRT2 = Math.sqrt(2);
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** What a block means for a walking body. */
    public enum Cell {
        /** Empty space. */
        AIR,
        /** Non-solid but occupiable (plants, torches, snow layers...). */
        PASSABLE,
        /** Full solid block: can stand on it, cannot be inside it. */
        SOLID,
        /** Solid but too tall to stand on cleanly (fences, walls). */
        TALL,
        WATER,
        /** Wooden door or fence gate: walked through (the hunt opens it). */
        DOOR,
        /** Must never be entered or stood on (lava, fire, cactus, ...). */
        HAZARD,
        /** Not loaded: treated as a wall so a search never loads chunks. */
        UNLOADED
    }

    /** Block source; abstracted so the algorithm can be unit tested without a server. */
    public interface Blocks {
        Cell cell(int x, int y, int z);

        int minY();

        int maxY();
    }

    /** Adapter for a live world. Never loads chunks. */
    public static Blocks of(World world) {
        return new Blocks() {
            @Override
            public Cell cell(int x, int y, int z) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return Cell.UNLOADED;
                }
                return classify(world.getType(x, y, z));
            }

            @Override
            public int minY() {
                return world.getMinHeight();
            }

            @Override
            public int maxY() {
                return world.getMaxHeight();
            }
        };
    }

    /** Classification of a material. Requires a running server (material properties use registries). */
    public static Cell classify(Material m) {
        if (m.isAir()) {
            return Cell.AIR;
        }
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
            return Cell.WATER;
        }
        if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS
                || m == Material.MAGMA_BLOCK || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                || m == Material.COBWEB || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.WITHER_ROSE || m == Material.POINTED_DRIPSTONE) {
            return Cell.HAZARD;
        }
        String n = m.name();
        if (n.endsWith("_DOOR") && m != Material.IRON_DOOR || n.endsWith("_FENCE_GATE")) {
            return Cell.DOOR;
        }
        if (n.endsWith("TRAPDOOR") || n.equals("IRON_DOOR")) {
            return Cell.TALL;
        }
        if (m.isSolid()) {
            return n.endsWith("_FENCE") || n.endsWith("_WALL") ? Cell.TALL : Cell.SOLID;
        }
        return Cell.PASSABLE;
    }

    public record Point(int x, int y, int z) {
        long key() {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }

    /**
     * @param points     feet block positions from start (exclusive) to the end
     * @param reachesGoal whether the last point is the goal itself
     */
    public record Result(List<Point> points, boolean reachesGoal, int explored) {
    }

    private final Blocks blocks;
    private final Map<Long, Cell> cache = new HashMap<>();

    public GridPathfinder(Blocks blocks) {
        this.blocks = blocks;
    }

    private Cell cell(int x, int y, int z) {
        if (y < blocks.minY() || y >= blocks.maxY()) {
            return Cell.SOLID;
        }
        long k = new Point(x, y, z).key();
        Cell c = cache.get(k);
        if (c == null) {
            c = blocks.cell(x, y, z);
            cache.put(k, c);
        }
        return c;
    }

    /** A body part can occupy this block. */
    private boolean passable(int x, int y, int z) {
        Cell c = cell(x, y, z);
        return c == Cell.AIR || c == Cell.PASSABLE || c == Cell.WATER || c == Cell.DOOR;
    }

    private boolean water(int x, int y, int z) {
        return cell(x, y, z) == Cell.WATER;
    }

    /** Something solid to stand on. */
    private boolean support(int x, int y, int z) {
        return cell(x, y, z) == Cell.SOLID;
    }

    /** Whether a body can be at feet position (x,y,z). */
    public boolean canStand(int x, int y, int z) {
        if (!passable(x, y, z) || !passable(x, y + 1, z)) {
            return false;
        }
        return support(x, y - 1, z) || water(x, y, z);
    }

    private static double heuristic(int x, int y, int z, Point goal) {
        int dx = Math.abs(x - goal.x());
        int dz = Math.abs(z - goal.z());
        int dy = Math.abs(y - goal.y());
        return Math.max(dx, dz) + (SQRT2 - 1) * Math.min(dx, dz) + dy * 0.5;
    }

    private static final class Node implements Comparable<Node> {
        final Point p;
        final double g;
        final double f;
        final Node parent;

        Node(Point p, double g, double f, Node parent) {
            this.p = p;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    public Result find(Point start, Point goal, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Node first = new Node(start, 0, heuristic(start.x(), start.y(), start.z(), goal), null);
        open.add(first);
        best.put(start.key(), 0.0);
        Node closest = first;
        double closestH = first.f;
        int explored = 0;
        while (!open.isEmpty() && explored < maxNodes) {
            Node n = open.poll();
            Double known = best.get(n.p.key());
            if (known != null && n.g > known + 1.0E-9) {
                continue;
            }
            explored++;
            if (n.p.equals(goal)) {
                return new Result(trace(n), true, explored);
            }
            double h = heuristic(n.p.x(), n.p.y(), n.p.z(), goal);
            if (h < closestH) {
                closestH = h;
                closest = n;
            }
            expand(n, goal, open, best);
        }
        return new Result(trace(closest), false, explored);
    }

    private void expand(Node n, Point goal, PriorityQueue<Node> open, Map<Long, Double> best) {
        int x = n.p.x();
        int y = n.p.y();
        int z = n.p.z();
        boolean inWater = water(x, y, z);
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (!passable(x + d[0], y, z) || !passable(x + d[0], y + 1, z)
                    || !passable(x, y, z + d[1]) || !passable(x, y + 1, z + d[1]))) {
                continue;
            }
            double base = diagonal ? SQRT2 : 1.0;
            if (canStand(nx, y, nz)) {
                push(n, new Point(nx, y, nz), base + extra(nx, y, nz), goal, open, best);
                continue;
            }
            // Step up one block: needs head room above the current position.
            if (!diagonal && canStand(nx, y + 1, nz) && passable(x, y + 2, z)) {
                push(n, new Point(nx, y + 1, nz), base + 0.6 + extra(nx, y + 1, nz), goal, open, best);
                continue;
            }
            // Walk off an edge and drop.
            if (!diagonal && passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    if (canStand(nx, y - drop, nz)) {
                        push(n, new Point(nx, y - drop, nz), base + 0.3 * drop + extra(nx, y - drop, nz), goal, open, best);
                        break;
                    }
                    if (!passable(nx, y - drop, nz)) {
                        break;
                    }
                }
            }
        }
        if (inWater) {
            if (canStand(x, y + 1, z)) {
                push(n, new Point(x, y + 1, z), 2.0, goal, open, best);
            }
            if (water(x, y - 1, z) && canStand(x, y - 1, z)) {
                push(n, new Point(x, y - 1, z), 2.0, goal, open, best);
            }
        }
    }

    private double extra(int x, int y, int z) {
        double cost = 0;
        if (water(x, y, z)) {
            cost += 1.5;
        }
        if (cell(x, y, z) == Cell.DOOR) {
            cost += 1.0;
        }
        return cost;
    }

    private static void push(Node from, Point p, double cost, Point goal, PriorityQueue<Node> open, Map<Long, Double> best) {
        double g = from.g + cost;
        Double known = best.get(p.key());
        if (known != null && known <= g) {
            return;
        }
        best.put(p.key(), g);
        open.add(new Node(p, g, g + heuristic(p.x(), p.y(), p.z(), goal), from));
    }

    private static List<Point> trace(Node end) {
        List<Point> out = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) {
            out.add(n.p);
        }
        Collections.reverse(out);
        return out;
    }

    /** Converts a location to the feet block it occupies. */
    public static Point feet(Location l) {
        return new Point(l.getBlockX(), (int) Math.floor(l.getY() + 0.01), l.getBlockZ());
    }
}
