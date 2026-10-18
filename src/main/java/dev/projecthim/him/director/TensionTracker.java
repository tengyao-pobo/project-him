package dev.projecthim.him.director;

import dev.projecthim.him.util.MathUtil;
import java.util.random.RandomGenerator;

/**
 * Per-player pacing in the spirit of an "AI director": tension builds up with events, peaks, and is
 * then deliberately allowed to drain away during a relax phase in which HIM stays (almost) silent.
 * Thresholds and durations are re-rolled every cycle, so the rhythm never becomes a schedule.
 */
public final class TensionTracker {

    public enum Phase {
        BUILD_UP, PEAK, RELAX
    }

    private static final long HALF_LIFE_MS = 15 * 60_000L;

    private double tension;
    private long updatedAt;
    private Phase phase = Phase.BUILD_UP;
    private long phaseSince;
    private long phaseLength;
    private double peakThreshold = 0.7;
    private long lastEventAt;
    private final long createdAt;

    public TensionTracker(long now, RandomGenerator random) {
        this.updatedAt = now;
        this.phaseSince = now;
        this.createdAt = now;
        rollPeakThreshold(random);
    }

    private void rollPeakThreshold(RandomGenerator random) {
        peakThreshold = 0.55 + random.nextDouble() * 0.3;
    }

    private void decay(long now) {
        tension *= MathUtil.halfLifeFactor(now - updatedAt, HALF_LIFE_MS);
        updatedAt = Math.max(updatedAt, now);
    }

    /** Advances decay and the phase machine. Call before reading any value. */
    public void update(long now, RandomGenerator random) {
        decay(now);
        long inPhase = now - phaseSince;
        switch (phase) {
            case BUILD_UP -> {
                if (tension >= peakThreshold) {
                    enter(Phase.PEAK, now, 60_000L + (long) (random.nextDouble() * 90_000L));
                }
            }
            case PEAK -> {
                if (inPhase >= phaseLength) {
                    enter(Phase.RELAX, now, 3 * 60_000L + (long) (random.nextDouble() * 6 * 60_000L));
                }
            }
            case RELAX -> {
                if (inPhase >= phaseLength && tension < 0.25) {
                    enter(Phase.BUILD_UP, now, 0);
                    rollPeakThreshold(random);
                }
            }
        }
    }

    private void enter(Phase next, long now, long length) {
        phase = next;
        phaseSince = now;
        phaseLength = length;
    }

    public void onEvent(double intensity, long now) {
        decay(now);
        tension = MathUtil.clamp(tension + intensity * 0.9 * (1.0 - tension * 0.35), 0, 1);
        lastEventAt = now;
    }

    /** Highest event intensity allowed right now. */
    public double intensityCap() {
        return switch (phase) {
            // The cap opens up as tension builds, so the strongest moments come at the end of a build-up.
            case BUILD_UP -> MathUtil.clamp(0.35 + tension * 1.25, 0, 1);
            case PEAK -> 0.3;
            case RELAX -> 0.15;
        };
    }

    /** Multiplier on the chance to act at all. */
    public double hazardMultiplier() {
        return switch (phase) {
            case BUILD_UP -> 1.0;
            case PEAK -> 0.35;
            case RELAX -> 0.1;
        };
    }

    /** Reference time for "how long has it been quiet": last event or when tracking started. */
    public long quietSince() {
        return Math.max(lastEventAt, createdAt);
    }

    public double tension() {
        return tension;
    }

    public Phase phase() {
        return phase;
    }

    public long lastEventAt() {
        return lastEventAt;
    }

    /** Test / admin hook. */
    public void forcePhase(Phase phase, long now) {
        enter(phase, now, phase == Phase.BUILD_UP ? 0 : 60_000L);
    }
}
