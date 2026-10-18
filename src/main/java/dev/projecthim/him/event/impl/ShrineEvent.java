package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * The legendary shrine: gold, netherrack, fire, redstone torches, mossy cobblestone, appearing out
 * of sight while the player looks elsewhere. Purely client side. Walk up to it and all of it is gone
 * at once.
 */
final class ShrineEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("shrine", Intent.INTERFERE)
            .stage(Stage.WATCHED)
            .intensity(0.5)
            .cooldownMinutes(120)
            .weight(0.8)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .requires(c -> !c.underground() && c.dimension() == PlayerContext.Dimension.OVERWORLD && !c.inWater())
            .fit(c -> c.night() ? 1.4 : 0.9)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return ctx.him().fakes().enabled() ? new Instance(ctx) : null;
    }

    private static final class Instance extends EventInstance {
        private final List<Player> viewers;
        private Location center;
        private final Map<Block, BlockData> layout = new LinkedHashMap<>();
        private int seenTicks;

        Instance(EventContext ctx) {
            super(ctx);
            viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(ctx.target());
        }

        @Override
        protected boolean start() {
            for (int attempt = 0; attempt < 6 && center == null; attempt++) {
                Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(ctx.target(), 16, 28);
                if (spot.isPresent() && flat(spot.get().feet())) {
                    center = spot.get().feet();
                }
            }
            if (center == null) {
                return false;
            }
            buildLayout();
            long tick = ctx.him().tick();
            for (Player v : viewers) {
                for (Map.Entry<Block, BlockData> e : layout.entrySet()) {
                    ctx.him().fakes().show(v, e.getKey(), e.getValue(), 20 * 200, tick, ctx.instanceId());
                }
            }
            setWhere(center);
            return true;
        }

        /** 5x5 of solid ground with two blocks of air above the inner 3x3. */
        private static boolean flat(Location c) {
            World w = c.getWorld();
            int y = c.getBlockY();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int x = c.getBlockX() + dx;
                    int z = c.getBlockZ() + dz;
                    if (!Ground.loaded(w, x, z) || !Ground.standOn(w.getType(x, y - 1, z))) {
                        return false;
                    }
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                            && (!w.getType(x, y, z).isAir() || !w.getType(x, y + 1, z).isAir())) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void buildLayout() {
            World w = center.getWorld();
            int cx = center.getBlockX();
            int cy = center.getBlockY();
            int cz = center.getBlockZ();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                    // Replacing solid ground with solid blocks: collision stays consistent.
                    layout.put(w.getBlockAt(cx + dx, cy - 1, cz + dz),
                            (inner ? Material.GOLD_BLOCK : Material.MOSSY_COBBLESTONE).createBlockData());
                }
            }
            layout.put(w.getBlockAt(cx, cy, cz), Material.NETHERRACK.createBlockData());
            layout.put(w.getBlockAt(cx, cy + 1, cz), Material.FIRE.createBlockData());
            int[][] torches = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] t : torches) {
                layout.put(w.getBlockAt(cx + t[0], cy, cz + t[1]), Material.REDSTONE_TORCH.createBlockData());
            }
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            Location fire = center.clone().add(0.5, 1, 0.5);
            if (p.getWorld() != fire.getWorld()) {
                finish(Outcome.INTERRUPTED);
                return;
            }
            double d = p.getLocation().distance(fire);
            if (Sight.viewAngle(p, fire) < 20 && Sight.visibility(fire.getWorld(), p.getEyeLocation().toVector(),
                    fire.toVector()) > 0.5 && ++seenTicks >= 10) {
                setOutcome(p.getUniqueId(), Outcome.NOTICED);
            }
            for (Player v : viewers) {
                if (v.isOnline() && v.getWorld() == fire.getWorld() && v.getLocation().distance(fire) < 6) {
                    setOutcome(v.getUniqueId(), Outcome.APPROACHED);
                    finish(Outcome.APPROACHED);
                    return;
                }
            }
            if (d > 80 || age(tick) > 20 * 180) {
                finish(Outcome.UNNOTICED);
            }
        }

        @Override
        protected void cleanup() {
            ctx.him().fakes().revertOwner(ctx.instanceId());
        }

        @Override
        protected int maxTicks() {
            return 20 * 200;
        }
    }
}
