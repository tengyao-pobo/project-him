package dev.projecthim.him.player;

import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.TensionTracker;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.util.Vec;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Location;

/** Runtime state of an online player. Lives from join to quit, never persisted directly. */
public final class PlayerSession {

    private final UUID id;
    private final PlayerMemory memory;
    private final TensionTracker tension;
    private final long joinedAt;

    PlayerContext context;
    Location lastSampleLocation;
    long lastSampleAt;
    long lastMoveAt;
    float lastYaw;
    float lastPitch;
    double speedEma;
    Vec velocity = Vec.ZERO;
    long lastCombatAt;
    int placedSinceObserve;
    int brokenSinceObserve;
    long lastObserveAt;
    int samplesSinceObserve;
    long nextEvaluationTick;
    HeatMap.BaseEstimate baseEstimate;
    long baseEstimatedAt;
    long lastLandmarkTriggerAt;
    private org.bukkit.block.data.BlockData lastBroken;
    private Location lastBrokenAt;

    PlayerSession(UUID id, PlayerMemory memory, long now, RandomGenerator random) {
        this.id = id;
        this.memory = memory;
        this.tension = new TensionTracker(now, random);
        this.joinedAt = now;
        this.lastMoveAt = now;
        this.lastObserveAt = now;
    }

    public UUID id() {
        return id;
    }

    public PlayerMemory memory() {
        return memory;
    }

    public TensionTracker tension() {
        return tension;
    }

    public long joinedAt() {
        return joinedAt;
    }

    /** Latest sampled context, or null before the first sample. */
    public PlayerContext context() {
        return context;
    }

    /** Smoothed velocity in blocks per tick. */
    public Vec velocity() {
        return velocity;
    }

    public HeatMap.BaseEstimate baseEstimate() {
        return baseEstimate;
    }

    public void markCombat(long now) {
        lastCombatAt = now;
    }

    public void countPlaced() {
        placedSinceObserve++;
    }

    public void countBroken() {
        brokenSinceObserve++;
    }

    public void rememberBroken(org.bukkit.block.data.BlockData data, Location at) {
        this.lastBroken = data;
        this.lastBrokenAt = at.clone();
    }

    /** The last block the player broke, used by mimicry. May be null. */
    public org.bukkit.block.data.BlockData lastBroken() {
        return lastBroken;
    }

    public Location lastBrokenAt() {
        return lastBrokenAt;
    }

    public long nextEvaluationTick() {
        return nextEvaluationTick;
    }

    public void setNextEvaluationTick(long tick) {
        this.nextEvaluationTick = tick;
    }

    public long lastLandmarkTriggerAt() {
        return lastLandmarkTriggerAt;
    }

    public void setLastLandmarkTriggerAt(long time) {
        this.lastLandmarkTriggerAt = time;
    }

    public long idleMillis(long now) {
        return now - lastMoveAt;
    }
}
