package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;

/**
 * Exponential moving averages summarising how a player plays, plus reaction counters. This is the
 * "behaviour summary" HIM uses instead of raw history.
 */
public final class BehaviorProfile {

    /** Smoothing per sample. With one sample every ~10 seconds this covers roughly the last hour. */
    private static final double ALPHA = 0.003;

    private double undergroundRatio;
    private double nightOutdoorRatio;
    private double aloneRatio = 1.0;
    private double sprintRatio;
    private double idleRatio;
    private double averageSpeed;
    private double buildsPerMinute;
    private double minesPerMinute;
    private long samples;

    private int sightings;
    private int noticed;
    private int approached;
    private int fled;
    private int attacked;
    private int ignored;

    public enum Temperament {
        UNKNOWN, OBLIVIOUS, CURIOUS, SKITTISH, AGGRESSIVE
    }

    public void observe(boolean underground, boolean nightOutdoors, boolean alone, boolean sprinting, boolean idle,
                        double speed, double buildsPerMin, double minesPerMin) {
        double a = samples < 30 ? 1.0 / (samples + 1) : ALPHA;
        undergroundRatio = MathUtil.ema(undergroundRatio, underground ? 1 : 0, a);
        nightOutdoorRatio = MathUtil.ema(nightOutdoorRatio, nightOutdoors ? 1 : 0, a);
        aloneRatio = MathUtil.ema(aloneRatio, alone ? 1 : 0, a);
        sprintRatio = MathUtil.ema(sprintRatio, sprinting ? 1 : 0, a);
        idleRatio = MathUtil.ema(idleRatio, idle ? 1 : 0, a);
        averageSpeed = MathUtil.ema(averageSpeed, Math.max(0, speed), a);
        buildsPerMinute = MathUtil.ema(buildsPerMinute, Math.max(0, buildsPerMin), a);
        minesPerMinute = MathUtil.ema(minesPerMinute, Math.max(0, minesPerMin), a);
        samples++;
    }

    public void recordReaction(Outcome outcome) {
        switch (outcome) {
            case UNNOTICED -> {
                sightings++;
                ignored++;
            }
            case NOTICED -> {
                sightings++;
                noticed++;
            }
            case APPROACHED -> {
                sightings++;
                noticed++;
                approached++;
            }
            case FLED -> {
                sightings++;
                noticed++;
                fled++;
            }
            case ATTACKED -> {
                sightings++;
                noticed++;
                attacked++;
            }
            default -> {
                // Hunt outcomes are tracked by ChaseStats.
            }
        }
    }

    public Temperament temperament() {
        if (sightings < 3) {
            return Temperament.UNKNOWN;
        }
        if (noticed < sightings * 0.3) {
            return Temperament.OBLIVIOUS;
        }
        int max = Math.max(approached, Math.max(fled, attacked));
        if (max == 0) {
            return Temperament.OBLIVIOUS;
        }
        if (max == attacked) {
            return Temperament.AGGRESSIVE;
        }
        if (max == fled) {
            return Temperament.SKITTISH;
        }
        return Temperament.CURIOUS;
    }

    /** Share of visual encounters the player actually noticed, with a neutral prior. */
    public double noticeRate() {
        return (noticed + 1.0) / (sightings + 2.0);
    }

    public double undergroundRatio() {
        return undergroundRatio;
    }

    public double nightOutdoorRatio() {
        return nightOutdoorRatio;
    }

    public double aloneRatio() {
        return aloneRatio;
    }

    public double sprintRatio() {
        return sprintRatio;
    }

    public double idleRatio() {
        return idleRatio;
    }

    public double averageSpeed() {
        return averageSpeed;
    }

    public double buildsPerMinute() {
        return buildsPerMinute;
    }

    public double minesPerMinute() {
        return minesPerMinute;
    }

    public int sightings() {
        return sightings;
    }

    public int noticed() {
        return noticed;
    }

    public int approached() {
        return approached;
    }

    public int fled() {
        return fled;
    }

    public int attacked() {
        return attacked;
    }

    public int ignored() {
        return ignored;
    }

    public long samples() {
        return samples;
    }
}
