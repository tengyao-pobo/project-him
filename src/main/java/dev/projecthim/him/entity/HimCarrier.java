package dev.projecthim.him.entity;

import dev.projecthim.him.nav.GridPathfinder;
import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;

/**
 * The invisible "where HIM really is" during a hunt: a virtual position that walks the terrain.
 *
 * <p>Paths are planned by {@link GridPathfinder} for a player-sized body and followed kinematically
 * at an exact speed. No entity is involved, so hunts do not depend on mob AI, entity activation
 * ranges, or vanilla navigation quirks, and nothing can be left behind in the world. The visible
 * body simply follows this position.
 */
public final class HimCarrier {

    private Location position;
    private final List<Location> path = new ArrayList<>();
    private int index;
    private Location lastGoal;
    private long lastPathTick = Long.MIN_VALUE;
    private double speed;
    private boolean lastPlanReachedGoal;
    private boolean removed;

    HimCarrier(Location at) {
        this.position = at.clone();
    }

    /** Movement speed in blocks per second, applied by {@link #step()}. */
    public void setSpeed(double blocksPerSecond) {
        this.speed = Math.max(0, blocksPerSecond);
    }

    /**
     * Plans a path to the destination. Re-plans only when the goal moved noticeably or the plan is
     * older than {@code replanTicks}, which keeps the planning cost low.
     *
     * @return false if no path is known
     */
    public boolean navigate(Location destination, long tick, int replanTicks) {
        boolean stale = tick - lastPathTick >= replanTicks;
        boolean moved = lastGoal == null || lastGoal.getWorld() != destination.getWorld()
                || lastGoal.distanceSquared(destination) > 2.25;
        if (index >= path.size() || stale || moved) {
            lastPathTick = tick;
            lastGoal = destination.clone();
            plan(destination);
        }
        return index < path.size();
    }

    private void plan(Location destination) {
        path.clear();
        index = 0;
        if (destination.getWorld() != position.getWorld()) {
            return;
        }
        GridPathfinder finder = new GridPathfinder(GridPathfinder.of(position.getWorld()));
        GridPathfinder.Point start = GridPathfinder.feet(position);
        GridPathfinder.Point goal = groundGoal(finder, destination);
        GridPathfinder.Result r = finder.find(start, goal, GridPathfinder.DEFAULT_MAX_NODES);
        lastPlanReachedGoal = r.reachesGoal();
        for (GridPathfinder.Point p : r.points()) {
            path.add(new Location(position.getWorld(), p.x() + 0.5, p.y(), p.z() + 0.5));
        }
    }

    /** Snaps a goal (often a player's position, possibly mid-jump) to a standable block below it. */
    private static GridPathfinder.Point groundGoal(GridPathfinder finder, Location destination) {
        GridPathfinder.Point p = GridPathfinder.feet(destination);
        for (int dy = 0; dy <= 4; dy++) {
            if (finder.canStand(p.x(), p.y() - dy, p.z())) {
                return new GridPathfinder.Point(p.x(), p.y() - dy, p.z());
            }
        }
        return p;
    }

    /** Advances along the planned path by one tick worth of movement. */
    public void step() {
        if (index >= path.size() || speed <= 0) {
            return;
        }
        double budget = speed / 20.0;
        Location next = position.clone();
        while (budget > 0 && index < path.size()) {
            Location node = path.get(index);
            double dist = next.distance(node);
            if (dist <= budget) {
                next = node.clone();
                budget -= dist;
                index++;
            } else {
                double f = budget / dist;
                next.add((node.getX() - next.getX()) * f, (node.getY() - next.getY()) * f, (node.getZ() - next.getZ()) * f);
                budget = 0;
            }
        }
        if (!next.getWorld().isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) {
            path.clear();
            return;
        }
        double dx = next.getX() - position.getX();
        double dz = next.getZ() - position.getZ();
        next.setYaw(dx * dx + dz * dz > 1.0E-6 ? MathUtil.yawTowards(position.getX(), position.getZ(), next.getX(),
                next.getZ()) : position.getYaw());
        next.setPitch(0);
        position = next;
    }

    public void stop() {
        path.clear();
        index = 0;
        lastGoal = null;
    }

    public int pathSize() {
        return path.size();
    }

    public boolean hasPath() {
        return index < path.size();
    }

    /** Whether the last plan reached its goal (false means it ends as close as it could get). */
    public boolean lastPlanReachedGoal() {
        return lastPlanReachedGoal;
    }

    public void teleport(Location to) {
        stop();
        position = to.clone();
    }

    public Location location() {
        return position.clone();
    }

    public boolean valid() {
        return !removed && position.getWorld() != null
                && position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4);
    }

    void remove() {
        removed = true;
        path.clear();
    }
}
