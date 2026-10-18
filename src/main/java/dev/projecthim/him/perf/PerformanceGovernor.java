package dev.projecthim.him.perf;

import dev.projecthim.him.config.HimConfig;
import org.bukkit.Bukkit;

/**
 * Watches server health and HIM's own cost, and tells every subsystem how much it may do.
 *
 * <ul>
 *     <li>HEALTHY: everything runs.</li>
 *     <li>STRAINED: fewer events, no new hunts, slower context sampling.</li>
 *     <li>CRITICAL: no new events; running encounters are wound down.</li>
 * </ul>
 * A small hysteresis avoids flapping between levels.
 */
public final class PerformanceGovernor {

    public enum Level {
        HEALTHY, STRAINED, CRITICAL
    }

    private HimConfig.Performance settings;
    private Level level = Level.HEALTHY;
    private double serverMspt;
    private double serverTps = 20;
    /** Exponential average of HIM's own work per tick in milliseconds. */
    private double ownMs;
    private long tickStart;
    private long levelSince = System.currentTimeMillis();
    private final long startedAt = System.currentTimeMillis();
    private String reason = "";

    public PerformanceGovernor(HimConfig.Performance settings) {
        this.settings = settings;
    }

    public void configure(HimConfig.Performance settings) {
        this.settings = settings;
    }

    public void beginTick() {
        tickStart = System.nanoTime();
    }

    public void endTick() {
        double ms = (System.nanoTime() - tickStart) / 1_000_000.0;
        ownMs = ownMs * 0.95 + ms * 0.05;
    }

    /** Samples server metrics. Call about once per second. */
    public void sample() {
        serverMspt = Bukkit.getAverageTickTime();
        double[] tps = Bukkit.getTPS();
        serverTps = tps.length > 0 ? tps[0] : 20;
        // The one minute TPS average is meaningless right after startup.
        boolean tpsReliable = System.currentTimeMillis() - startedAt > 90_000;
        Level next;
        if (serverMspt >= settings.msptCritical()) {
            next = Level.CRITICAL;
            reason = String.format(java.util.Locale.ROOT, "%.1f mspt", serverMspt);
        } else if (tpsReliable && serverTps < settings.tpsCritical()) {
            next = Level.CRITICAL;
            reason = String.format(java.util.Locale.ROOT, "%.1f TPS", serverTps);
        } else if (serverMspt >= settings.msptStrained()) {
            next = Level.STRAINED;
            reason = String.format(java.util.Locale.ROOT, "%.1f mspt", serverMspt);
        } else if (ownMs > settings.ownBudgetMs()) {
            next = Level.STRAINED;
            reason = String.format(java.util.Locale.ROOT, "HIM %.2f ms/tick", ownMs);
        } else {
            next = Level.HEALTHY;
            reason = "";
        }
        // Recover only after conditions are clearly better for a while.
        if (next.ordinal() < level.ordinal()) {
            boolean clearlyBetter = serverMspt < settings.msptStrained() * 0.85 || next == Level.STRAINED;
            if (!clearlyBetter || System.currentTimeMillis() - levelSince < 15_000) {
                return;
            }
        }
        if (next != level) {
            level = next;
            levelSince = System.currentTimeMillis();
        }
    }

    public Level level() {
        return level;
    }

    /** Multiplier for the director's chance to act. */
    public double directorMultiplier() {
        return switch (level) {
            case HEALTHY -> 1.0;
            case STRAINED -> 0.35;
            case CRITICAL -> 0.0;
        };
    }

    public boolean allowNewHunts() {
        return level == Level.HEALTHY;
    }

    /** Context sampling period in ticks per player. */
    public int samplePeriodTicks() {
        return level == Level.HEALTHY ? 20 : 40;
    }

    /** Why the current level was chosen, for logs and /him status. */
    public String reason() {
        return reason;
    }

    public double serverMspt() {
        return serverMspt;
    }

    public double serverTps() {
        return serverTps;
    }

    public double ownMs() {
        return ownMs;
    }
}
