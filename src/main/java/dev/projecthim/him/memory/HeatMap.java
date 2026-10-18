package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, decaying map of where a player spends time. Cells are 16x16 block columns. Each cell
 * keeps decaying counters for different kinds of evidence (dwell time, sleeping, opening containers,
 * building, dying) and a centroid of observed positions, which lets HIM guess where the player's
 * base is and which routes they use without storing any per-tick history.
 */
public final class HeatMap {

    public enum Signal {
        SLEEP, CONTAINER, BUILD, DEATH
    }

    public static final int CELL_SHIFT = 4;

    private Map<String, Cell> cells = new HashMap<>();

    public static final class Cell {
        String world;
        int cx;
        int cz;
        double dwellSeconds;
        double sleep;
        double containers;
        double builds;
        double deaths;
        double sumX;
        double sumY;
        double sumZ;
        double weightSum;
        long lastVisit;
        long decayedAt;

        Cell() {
        }

        Cell(String world, int cx, int cz, long now) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.decayedAt = now;
            this.lastVisit = now;
        }

        void decay(long now, long halfLifeMs) {
            double f = MathUtil.halfLifeFactor(now - decayedAt, halfLifeMs);
            if (f < 1.0) {
                dwellSeconds *= f;
                sleep *= f;
                containers *= f;
                builds *= f;
                deaths *= f;
                sumX *= f;
                sumY *= f;
                sumZ *= f;
                weightSum *= f;
            }
            decayedAt = Math.max(decayedAt, now);
        }

        void addPosition(double x, double y, double z, double weight) {
            sumX += x * weight;
            sumY += y * weight;
            sumZ += z * weight;
            weightSum += weight;
        }

        /** How "home like" this cell is. Sleeping and storage weigh far more than passing by. */
        public double homeScore() {
            return dwellSeconds / 60.0 + sleep * 40.0 + containers * 3.0 + builds * 0.4 - deaths * 2.0;
        }

        public double activityScore() {
            return dwellSeconds / 60.0 + builds * 0.2 + containers;
        }

        public String world() {
            return world;
        }

        public int cellX() {
            return cx;
        }

        public int cellZ() {
            return cz;
        }

        public double dwellSeconds() {
            return dwellSeconds;
        }

        public double centroidX() {
            return weightSum > 0 ? sumX / weightSum : (cx << CELL_SHIFT) + 8;
        }

        public double centroidY() {
            return weightSum > 0 ? sumY / weightSum : 64;
        }

        public double centroidZ() {
            return weightSum > 0 ? sumZ / weightSum : (cz << CELL_SHIFT) + 8;
        }

        public long lastVisit() {
            return lastVisit;
        }
    }

    /** Best guess of a player's home. Confidence is in [0,1]. */
    public record BaseEstimate(String world, double x, double y, double z, double confidence) {
    }

    private static String key(String world, int cx, int cz) {
        return world + ':' + cx + ':' + cz;
    }

    private Cell cell(String world, int x, int z, long now) {
        int cx = x >> CELL_SHIFT;
        int cz = z >> CELL_SHIFT;
        return cells.computeIfAbsent(key(world, cx, cz), k -> new Cell(world, cx, cz, now));
    }

    public void recordDwell(String world, double x, double y, double z, double seconds, long now,
                            long halfLifeMs, int capacity) {
        Cell c = cell(world, (int) Math.floor(x), (int) Math.floor(z), now);
        c.decay(now, halfLifeMs);
        c.dwellSeconds += seconds;
        c.addPosition(x, y, z, seconds);
        c.lastVisit = now;
        trim(capacity, now, halfLifeMs);
    }

    public void recordSignal(String world, double x, double y, double z, Signal signal, long now,
                             long halfLifeMs, int capacity) {
        Cell c = cell(world, (int) Math.floor(x), (int) Math.floor(z), now);
        c.decay(now, halfLifeMs);
        double positionWeight;
        switch (signal) {
            case SLEEP -> {
                c.sleep += 1;
                positionWeight = 120;
            }
            case CONTAINER -> {
                c.containers += 1;
                positionWeight = 20;
            }
            case BUILD -> {
                c.builds += 1;
                positionWeight = 2;
            }
            case DEATH -> {
                c.deaths += 1;
                positionWeight = 0;
            }
            default -> throw new IllegalStateException("Unexpected signal " + signal);
        }
        if (positionWeight > 0) {
            c.addPosition(x, y, z, positionWeight);
        }
        trim(capacity, now, halfLifeMs);
    }

    private void trim(int capacity, long now, long halfLifeMs) {
        if (cells.size() <= capacity) {
            return;
        }
        List<Map.Entry<String, Cell>> entries = new ArrayList<>(cells.entrySet());
        for (Map.Entry<String, Cell> e : entries) {
            e.getValue().decay(now, halfLifeMs);
        }
        entries.sort(Comparator.comparingDouble(e -> e.getValue().homeScore()));
        int remove = cells.size() - capacity;
        for (int i = 0; i < remove; i++) {
            cells.remove(entries.get(i).getKey());
        }
    }

    public List<Cell> hottest(String world, int limit, long now, long halfLifeMs) {
        List<Cell> list = new ArrayList<>();
        for (Cell c : cells.values()) {
            if (c.world.equals(world)) {
                c.decay(now, halfLifeMs);
                list.add(c);
            }
        }
        list.sort(Comparator.comparingDouble(Cell::activityScore).reversed());
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : List.copyOf(list);
    }

    /**
     * Estimates the base by clustering the top cell with its 8 neighbours. Confidence grows with the
     * dominance of that cluster over all evidence in the world and with absolute evidence.
     */
    public Optional<BaseEstimate> estimateBase(String world, long now, long halfLifeMs) {
        Cell best = null;
        double total = 0;
        for (Cell c : cells.values()) {
            if (!c.world.equals(world)) {
                continue;
            }
            c.decay(now, halfLifeMs);
            double s = Math.max(0, c.homeScore());
            total += s;
            if (best == null || s > Math.max(0, best.homeScore())) {
                best = c;
            }
        }
        if (best == null || best.homeScore() <= 1.0) {
            return Optional.empty();
        }
        double cluster = 0;
        double sx = 0;
        double sy = 0;
        double sz = 0;
        double w = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Cell n = cells.get(key(world, best.cx + dx, best.cz + dz));
                if (n == null) {
                    continue;
                }
                double s = Math.max(0, n.homeScore());
                cluster += s;
                if (n.weightSum > 0) {
                    sx += n.sumX;
                    sy += n.sumY;
                    sz += n.sumZ;
                    w += n.weightSum;
                }
            }
        }
        double dominance = total > 0 ? cluster / total : 0;
        double evidence = 1.0 - Math.exp(-cluster / 60.0);
        double confidence = MathUtil.clamp(dominance * evidence, 0, 1);
        if (w <= 0) {
            return Optional.of(new BaseEstimate(world, best.centroidX(), best.centroidY(), best.centroidZ(), confidence));
        }
        return Optional.of(new BaseEstimate(world, sx / w, sy / w, sz / w, confidence));
    }

    public int size() {
        return cells.size();
    }

    public void clear() {
        cells.clear();
    }

    void sanitize() {
        cells.values().removeIf(c -> c == null || c.world == null);
    }
}
