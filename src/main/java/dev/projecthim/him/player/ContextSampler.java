package dev.projecthim.him.player;

import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;
import dev.projecthim.him.util.MathUtil;
import dev.projecthim.him.util.Vec;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Turns a player's live state into a {@link PlayerContext} and, as a side effect, feeds HIM's
 * memory: dwell time in the heat map, the behaviour profile and companion time. Runs about once per
 * second per player, staggered across ticks.
 */
public final class ContextSampler {

    private static final long IDLE_AFK_MS = 5 * 60_000L;
    private static final long OBSERVE_EVERY_MS = 10_000L;
    private static final long BASE_REFRESH_MS = 30_000L;

    private ContextSampler() {
    }

    public static PlayerContext sample(Player player, PlayerSession s, WorldState world, HimConfig config,
                                       Collection<? extends Player> nearbyEligible, long now) {
        Location loc = player.getLocation();
        World w = loc.getWorld();
        long dtMs = s.lastSampleAt == 0 ? 1000 : Math.max(1, now - s.lastSampleAt);

        // Movement, speed and idleness.
        if (s.lastSampleLocation != null && s.lastSampleLocation.getWorld() == w) {
            double dx = loc.getX() - s.lastSampleLocation.getX();
            double dy = loc.getY() - s.lastSampleLocation.getY();
            double dz = loc.getZ() - s.lastSampleLocation.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double speed = horizontal / (dtMs / 1000.0);
            s.speedEma = MathUtil.ema(s.speedEma, speed, 0.5);
            double ticks = dtMs / 50.0;
            s.velocity = new Vec(dx / ticks, dy / ticks, dz / ticks);
            boolean rotated = Math.abs(MathUtil.yawDelta(s.lastYaw, loc.getYaw())) > 4
                    || Math.abs(s.lastPitch - loc.getPitch()) > 4;
            if (horizontal > 0.25 || Math.abs(dy) > 0.5 || rotated) {
                s.lastMoveAt = now;
            }
        } else {
            s.lastMoveAt = now;
        }
        s.lastSampleLocation = loc.clone();
        s.lastYaw = loc.getYaw();
        s.lastPitch = loc.getPitch();
        s.lastSampleAt = now;

        Block feet = loc.getBlock();
        int skyLight = feet.getLightFromSky();
        int light = feet.getLightLevel();
        int highest = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        boolean skyVisible = highest < loc.getBlockY() + 1 || skyLight >= 14;
        boolean underground = loc.getBlockY() < highest - 3 && skyLight <= 4;
        boolean roof = highest > loc.getBlockY() + 1;
        long time = w.getTime();
        boolean night = w.getEnvironment() == World.Environment.NORMAL && time >= 13_000 && time <= 23_000;

        PlayerMemory memory = s.memory();
        String worldName = w.getName();
        long halfLife = config.memory().heatHalfLifeMs();
        if (s.baseEstimate == null || now - s.baseEstimatedAt > BASE_REFRESH_MS
                || !s.baseEstimate.world().equals(worldName)) {
            s.baseEstimate = memory.heat().estimateBase(worldName, now, halfLife).orElse(null);
            s.baseEstimatedAt = now;
        }
        HeatMap.BaseEstimate base = s.baseEstimate;
        double distanceToBase = base == null ? Double.NaN
                : Math.sqrt(sq(base.x() - loc.getX()) + sq(base.y() - loc.getY()) + sq(base.z() - loc.getZ()));

        boolean afk = now - s.lastMoveAt > IDLE_AFK_MS;
        double seconds = dtMs / 1000.0;
        if (!afk) {
            memory.heat().recordDwell(worldName, loc.getX(), loc.getY(), loc.getZ(), seconds, now, halfLife,
                    config.memory().heatCapacity());
            memory.addPlayTime(Math.round(seconds), now);
        }
        for (Player other : nearbyEligible) {
            if (other != player && other.getLocation().distanceSquared(loc) < 24 * 24) {
                memory.addCompanionTime(other.getUniqueId(), seconds);
            }
        }

        int nearby = 0;
        double r2 = config.groupRadius() * config.groupRadius();
        for (Player other : nearbyEligible) {
            if (other != player && other.getWorld() == w && other.getLocation().distanceSquared(loc) <= r2) {
                nearby++;
            }
        }
        s.samplesSinceObserve++;
        if (now - s.lastObserveAt >= OBSERVE_EVERY_MS && !afk) {
            double minutes = (now - s.lastObserveAt) / 60_000.0;
            memory.behavior().observe(underground, night && skyVisible, nearby == 0,
                    player.isSprinting(), now - s.lastMoveAt > 15_000, s.speedEma,
                    s.placedSinceObserve / minutes, s.brokenSinceObserve / minutes);
            s.placedSinceObserve = 0;
            s.brokenSinceObserve = 0;
            s.lastObserveAt = now;
            s.samplesSinceObserve = 0;
        }

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double health = maxHealth == null ? 1 : player.getHealth() / Math.max(1, maxHealth.getValue());
        boolean nearLandmark = world.nearestLandmark(player.getUniqueId(), worldName, loc.getX(), loc.getY(),
                loc.getZ(), 20) != null;

        PlayerContext.Dimension dim = switch (w.getEnvironment()) {
            case NORMAL -> PlayerContext.Dimension.OVERWORLD;
            case NETHER -> PlayerContext.Dimension.NETHER;
            case THE_END -> PlayerContext.Dimension.END;
            default -> PlayerContext.Dimension.OTHER;
        };
        PlayerContext ctx = new PlayerContext(player.getUniqueId(), worldName, dim, night, w.hasStorm(),
                w.isThundering(), light, skyVisible, underground, player.isInWater(), player.isInsideVehicle(),
                player.isGliding() || player.isFlying(), player.isSleeping(), health, s.speedEma,
                (int) ((now - s.lastMoveAt) / 1000), player.isSprinting(), nearby, now - s.lastCombatAt < 8_000,
                distanceToBase, base == null ? 0 : base.confidence(), roof && !underground && light >= 8,
                nearLandmark, (now - s.joinedAt()) / 1000);
        s.context = ctx;
        return ctx;
    }

    private static double sq(double v) {
        return v * v;
    }
}
