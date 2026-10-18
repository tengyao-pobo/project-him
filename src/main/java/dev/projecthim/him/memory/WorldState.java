package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Server wide state of HIM: his personality, preferences and the places he remembers. */
public final class WorldState {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_LANDMARKS = 64;
    private static final int MAX_BUILT_CELLS = 4096;

    private int schemaVersion = SCHEMA_VERSION;
    private long createdAt;
    private Personality personality;
    private long totalEncounters;
    private long totalHunts;
    private ArrayList<Landmark> landmarks = new ArrayList<>();
    /** Learned preference per event id, multiplier around 1.0. */
    private LinkedHashMap<String, Double> eventAffinity = new LinkedHashMap<>();
    /**
     * Cells (16x16 columns, "world:cx:cz") where players placed many blocks. Used to keep real world
     * modifications away from anything players built.
     */
    private LinkedHashMap<String, Integer> builtCells = new LinkedHashMap<>();

    public WorldState() {
    }

    public static WorldState fresh(RandomGenerator random, long now) {
        WorldState s = new WorldState();
        s.createdAt = now;
        s.personality = Personality.seeded(random, now);
        return s;
    }

    public void sanitize(RandomGenerator random, long now) {
        if (personality == null) {
            personality = Personality.seeded(random, now);
        }
        personality.sanitize();
        if (landmarks == null) {
            landmarks = new ArrayList<>();
        }
        landmarks.removeIf(l -> l == null || l.kind() == null || l.world() == null);
        if (eventAffinity == null) {
            eventAffinity = new LinkedHashMap<>();
        }
        eventAffinity.replaceAll((k, v) -> v == null || !Double.isFinite(v) ? 1.0 : MathUtil.clamp(v, 0.4, 2.0));
        if (builtCells == null) {
            builtCells = new LinkedHashMap<>();
        }
        if (createdAt <= 0) {
            createdAt = now;
        }
        schemaVersion = SCHEMA_VERSION;
    }

    public Personality personality() {
        return personality;
    }

    public void countEncounter() {
        totalEncounters++;
    }

    public void countHunt() {
        totalHunts++;
    }

    public long totalEncounters() {
        return totalEncounters;
    }

    public long totalHunts() {
        return totalHunts;
    }

    public void addLandmark(Landmark landmark) {
        landmarks.add(landmark);
        while (landmarks.size() > MAX_LANDMARKS) {
            landmarks.removeFirst();
        }
    }

    public List<Landmark> landmarks() {
        return List.copyOf(landmarks);
    }

    public List<Landmark> landmarksOf(UUID player) {
        return landmarks.stream().filter(l -> player.equals(l.player())).toList();
    }

    /** Closest landmark of the player within {@code radius}, used by memory-linked events. */
    public Landmark nearestLandmark(UUID player, String world, double x, double y, double z, double radius) {
        double r2 = radius * radius;
        return landmarks.stream()
                .filter(l -> player.equals(l.player()))
                .filter(l -> l.distanceSquared(world, x, y, z) <= r2)
                .min(Comparator.comparingDouble(l -> l.distanceSquared(world, x, y, z)))
                .orElse(null);
    }

    public void removeLandmarksOf(UUID player) {
        landmarks.removeIf(l -> player.equals(l.player()));
    }

    public double affinity(String eventId) {
        return eventAffinity.getOrDefault(eventId, 1.0);
    }

    public void adjustAffinity(String eventId, double delta) {
        eventAffinity.put(eventId, MathUtil.clamp(affinity(eventId) + delta, 0.4, 2.0));
    }

    public void recordBuild(String world, int x, int z) {
        String key = world + ':' + (x >> HeatMap.CELL_SHIFT) + ':' + (z >> HeatMap.CELL_SHIFT);
        builtCells.merge(key, 1, Integer::sum);
        if (builtCells.size() > MAX_BUILT_CELLS) {
            // Drop the least built cell. Cheap enough at this bounded size and rare in practice.
            String weakest = builtCells.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            builtCells.remove(weakest);
        }
    }

    /** Number of player placed blocks recorded in the cell containing (x,z). */
    public int buildDensity(String world, int x, int z) {
        return builtCells.getOrDefault(world + ':' + (x >> HeatMap.CELL_SHIFT) + ':' + (z >> HeatMap.CELL_SHIFT), 0);
    }

    public long createdAt() {
        return createdAt;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
