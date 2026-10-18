package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything HIM remembers about one player. All collections are bounded. */
public final class PlayerMemory {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENCOUNTERS = 48;
    private static final int MAX_COMPANIONS = 12;

    private int schemaVersion = SCHEMA_VERSION;
    private UUID id;
    private String lastName = "";
    private long firstSeen;
    private long lastSeen;
    private long playSeconds;
    private int sessions;
    /** 0..1, how interested HIM is in this player specifically. */
    private double obsession = 0.05;
    /** -1 means automatic stage progression, otherwise a fixed stage set by an admin. */
    private int stageOverride = -1;
    /** Admin controlled participation. Null means "use server default". */
    private Boolean participating;

    private HeatMap heat = new HeatMap();
    private BehaviorProfile behavior = new BehaviorProfile();
    private ChaseStats chase = new ChaseStats();
    private ExpectationModel expectations = new ExpectationModel();
    private ArrayList<EncounterRecord> encounters = new ArrayList<>();
    private LinkedHashMap<String, Double> companions = new LinkedHashMap<>();
    private LinkedHashMap<String, Long> lastEventAt = new LinkedHashMap<>();
    /** Set when the player left during an encounter. Used for a memory-linked event on return. */
    private Landmark pendingReturn;

    public PlayerMemory() {
    }

    public PlayerMemory(UUID id, String name, long now) {
        this.id = id;
        this.lastName = name;
        this.firstSeen = now;
        this.lastSeen = now;
    }

    /** Fixes up nulls and out of range values that may come from older or hand edited files. */
    public void sanitize() {
        if (heat == null) {
            heat = new HeatMap();
        }
        heat.sanitize();
        if (behavior == null) {
            behavior = new BehaviorProfile();
        }
        if (chase == null) {
            chase = new ChaseStats();
        }
        if (expectations == null) {
            expectations = new ExpectationModel();
        }
        if (encounters == null) {
            encounters = new ArrayList<>();
        }
        encounters.removeIf(e -> e == null || e.outcome() == null || e.eventId() == null);
        if (companions == null) {
            companions = new LinkedHashMap<>();
        }
        if (lastEventAt == null) {
            lastEventAt = new LinkedHashMap<>();
        }
        if (lastName == null) {
            lastName = "";
        }
        obsession = Double.isFinite(obsession) ? MathUtil.clamp(obsession, 0, 1) : 0.05;
        if (stageOverride < -1 || stageOverride > 4) {
            stageOverride = -1;
        }
        schemaVersion = SCHEMA_VERSION;
    }

    public void onJoin(String name, long now) {
        this.lastName = name;
        this.lastSeen = now;
        this.sessions++;
    }

    public void addPlayTime(long seconds, long now) {
        playSeconds += seconds;
        lastSeen = now;
    }

    public void recordEncounter(EncounterRecord record) {
        encounters.add(record);
        while (encounters.size() > MAX_ENCOUNTERS) {
            encounters.removeFirst();
        }
        lastEventAt.put(record.eventId(), record.time());
    }

    public void markEventTime(String eventId, long now) {
        lastEventAt.put(eventId, now);
    }

    public long lastEventAt(String eventId) {
        return lastEventAt.getOrDefault(eventId, 0L);
    }

    public long lastAnyEventAt() {
        long max = 0;
        for (long t : lastEventAt.values()) {
            max = Math.max(max, t);
        }
        return max;
    }

    public void addCompanionTime(UUID other, double seconds) {
        companions.merge(other.toString(), seconds, Double::sum);
        if (companions.size() > MAX_COMPANIONS) {
            String weakest = companions.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (weakest != null) {
                companions.remove(weakest);
            }
        }
    }

    /** The player this player most often plays with, if any. */
    public UUID closestCompanion() {
        return companions.entrySet().stream()
                .filter(e -> e.getValue() > 600)
                .max(Map.Entry.comparingByValue())
                .map(e -> UUID.fromString(e.getKey()))
                .orElse(null);
    }

    public int notableEncounters() {
        int n = 0;
        for (EncounterRecord r : encounters) {
            if (r.outcome().isNotable()) {
                n++;
            }
        }
        return n;
    }

    public List<EncounterRecord> encounters() {
        return List.copyOf(encounters);
    }

    public List<EncounterRecord> recentEncounters(int limit) {
        List<EncounterRecord> copy = new ArrayList<>(encounters);
        copy.sort(Comparator.comparingLong(EncounterRecord::time).reversed());
        return copy.size() > limit ? List.copyOf(copy.subList(0, limit)) : List.copyOf(copy);
    }

    public void adjustObsession(double delta) {
        obsession = MathUtil.clamp(obsession + delta, 0, 1);
    }

    public UUID id() {
        return id;
    }

    public String lastName() {
        return lastName;
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public long playSeconds() {
        return playSeconds;
    }

    public int sessions() {
        return sessions;
    }

    public double obsession() {
        return obsession;
    }

    public int stageOverride() {
        return stageOverride;
    }

    public void setStageOverride(int stage) {
        this.stageOverride = MathUtil.clamp(stage, -1, 4);
    }

    public Boolean participating() {
        return participating;
    }

    public void setParticipating(Boolean participating) {
        this.participating = participating;
    }

    public HeatMap heat() {
        return heat;
    }

    public BehaviorProfile behavior() {
        return behavior;
    }

    public ChaseStats chase() {
        return chase;
    }

    public ExpectationModel expectations() {
        return expectations;
    }

    public Landmark pendingReturn() {
        return pendingReturn;
    }

    public void setPendingReturn(Landmark landmark) {
        this.pendingReturn = landmark;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
