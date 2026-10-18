package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.Sight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Behind the player, the torches go out. One by one, from the farthest. Client side only: the real
 * torches never changed, and they come back while the player is not looking at them.
 */
final class TorchSnuffEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("torch_snuff", Intent.INTERFERE)
            .stage(Stage.NOTICED)
            .intensity(0.4)
            .cooldownMinutes(30)
            .weight(1.3)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .requires(c -> c.underground() || c.night() || c.sheltered())
            .fit(c -> c.underground() ? 1.5 : 1.0)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        if (!ctx.him().fakes().enabled()) {
            return null;
        }
        List<Block> torches = findTorches(ctx.target(), 14);
        return torches.size() < 2 ? null : new Instance(ctx, torches);
    }

    private static boolean isTorch(Material m) {
        return m == Material.TORCH || m == Material.WALL_TORCH || m == Material.SOUL_TORCH || m == Material.SOUL_WALL_TORCH
                || m == Material.COPPER_TORCH || m == Material.COPPER_WALL_TORCH;
    }

    /** Torches behind the player, farthest from their view first. */
    static List<Block> findTorches(Player player, int radius) {
        Location l = player.getLocation();
        World w = l.getWorld();
        List<Block> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = l.getBlockX() + dx;
                int z = l.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -4; dy <= 5; dy++) {
                    if (isTorch(w.getType(x, l.getBlockY() + dy, z))) {
                        Block b = w.getBlockAt(x, l.getBlockY() + dy, z);
                        if (Sight.viewAngle(player, b.getLocation().add(0.5, 0.5, 0.5)) > 70) {
                            found.add(b);
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble((Block b) -> Sight.viewAngle(player, b.getLocation().add(0.5, 0.5, 0.5)))
                .reversed());
        return found.size() > 6 ? List.copyOf(found.subList(0, 6)) : found;
    }

    private static final class Instance extends EventInstance {
        private final List<Block> torches;
        private final List<Player> viewers;
        private final List<Block> snuffed = new ArrayList<>();
        private long nextAt;
        private long restoreAfter = -1;
        private int lookedAt;

        Instance(EventContext ctx, List<Block> torches) {
            super(ctx);
            this.torches = new ArrayList<>(torches);
            this.viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(ctx.target());
        }

        @Override
        protected boolean start() {
            setWhere(torches.getFirst().getLocation());
            nextAt = ctx.him().tick() + 10;
            return true;
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            for (Block b : snuffed) {
                if (Sight.viewAngle(p, b.getLocation().add(0.5, 0.5, 0.5)) < 25) {
                    if (++lookedAt >= 6) {
                        setOutcome(p.getUniqueId(), Outcome.NOTICED);
                    }
                    break;
                }
            }
            if (restoreAfter < 0) {
                if (tick < nextAt) {
                    return;
                }
                if (torches.isEmpty()) {
                    restoreAfter = tick + 100 + ctx.him().random().nextInt(120);
                    return;
                }
                Block b = torches.removeFirst();
                if (!isTorch(b.getType())) {
                    return;
                }
                boolean shown = false;
                for (Player v : viewers) {
                    shown |= ctx.him().fakes().show(v, b, Material.AIR.createBlockData(), 20 * 90, tick, ctx.instanceId());
                }
                if (shown) {
                    snuffed.add(b);
                    SoundCues.play(viewers, b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_CANDLE_EXTINGUISH,
                            SoundCategory.BLOCKS, 0.35f, 0.7f);
                }
                nextAt = tick + 15 + ctx.him().random().nextInt(15);
                return;
            }
            if (tick >= restoreAfter) {
                // Bring them back only while nobody is looking at them.
                boolean watched = false;
                for (Block b : snuffed) {
                    for (Player v : viewers) {
                        if (v.isOnline() && Sight.viewAngle(v, b.getLocation().add(0.5, 0.5, 0.5)) < 60) {
                            watched = true;
                        }
                    }
                }
                if (!watched || tick > restoreAfter + 20 * 40) {
                    finish(Outcome.UNNOTICED);
                }
            }
        }

        @Override
        protected void cleanup() {
            ctx.him().fakes().revertOwner(ctx.instanceId());
        }

        @Override
        protected int maxTicks() {
            return 20 * 100;
        }
    }
}
