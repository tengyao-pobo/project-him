package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.Ground;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Somewhere inside the rock, someone is mining. Bursts of pickaxe hits, a block breaking, silence,
 * and the next burst is a little closer. In a GROUP encounter every player hears it coming from a
 * different direction; in SHARED they all hear the same digger.
 */
final class CaveMiningEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("cave_mining", Intent.PRESENCE)
            .stage(Stage.UNAWARE)
            .intensity(0.3)
            .cooldownMinutes(25)
            .weight(1.3)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED, EncounterMode.GROUP)
            .requires(PlayerContext::underground)
            .fit(c -> c.alone() ? 1.3 : 1.0)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    /** One invisible digger, heard by some listeners. */
    private static final class Digger {
        final List<Player> listeners;
        double bearing;
        double distance;
        Block block;

        Digger(List<Player> listeners, double bearing, double distance) {
            this.listeners = listeners;
            this.bearing = bearing;
            this.distance = distance;
        }
    }

    private static final class Instance extends AudioInstance {
        private final Map<UUID, Digger> diggers = new HashMap<>();
        private final List<Digger> all = new java.util.ArrayList<>();
        private int burst;
        private int hitsLeft;
        private long nextSoundAt;
        private final int bursts;

        Instance(EventContext ctx) {
            super(ctx);
            bursts = 2 + ctx.him().random().nextInt(2);
        }

        @Override
        protected boolean start() {
            double distance = 14 + ctx.him().random().nextDouble() * 5;
            if (ctx.mode() == EncounterMode.GROUP) {
                for (Player p : ctx.participants()) {
                    add(new Digger(List.of(p), ctx.him().random().nextDouble() * 360, distance));
                }
            } else {
                List<Player> listeners = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(ctx.target());
                add(new Digger(listeners, ctx.him().random().nextDouble() * 360, distance));
            }
            for (Digger d : all) {
                if (!place(d)) {
                    return false;
                }
            }
            beginBurst(ctx.him().tick());
            return true;
        }

        private void add(Digger d) {
            all.add(d);
            for (Player p : d.listeners) {
                diggers.put(p.getUniqueId(), d);
            }
        }

        /** Finds a solid stone-like block, enclosed in rock, in the digger's direction. */
        private boolean place(Digger d) {
            Player anchor = d.listeners.getFirst();
            Location base = anchor.getLocation();
            World w = base.getWorld();
            for (int attempt = 0; attempt < 10; attempt++) {
                double rad = Math.toRadians(d.bearing + (attempt == 0 ? 0 : (ctx.him().random().nextDouble() - 0.5) * 60));
                int x = (int) Math.floor(base.getX() + Math.cos(rad) * d.distance);
                int z = (int) Math.floor(base.getZ() + Math.sin(rad) * d.distance);
                int y = base.getBlockY() + ctx.him().random().nextInt(5) - 2;
                if (!Ground.loaded(w, x, z)) {
                    continue;
                }
                Block b = w.getBlockAt(x, y, z);
                if (isRock(b.getType())) {
                    d.block = b;
                    setWhere(b.getLocation());
                    return true;
                }
            }
            return false;
        }

        private static boolean isRock(Material m) {
            return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.ANDESITE
                    || m == Material.DIORITE || m == Material.GRANITE || m == Material.NETHERRACK
                    || m == Material.COBBLED_DEEPSLATE || m == Material.CALCITE;
        }

        private void beginBurst(long tick) {
            hitsLeft = 5 + ctx.him().random().nextInt(4);
            nextSoundAt = tick + 4;
        }

        @Override
        protected void tick(long tick) {
            for (Digger d : all) {
                for (Player p : d.listeners) {
                    if (p.isOnline() && d.block != null) {
                        Location src = d.block.getLocation().add(0.5, 0.5, 0.5);
                        observe(p, src);
                        if (approached(p, src, 6)) {
                            // They are coming to look. The digging simply stops.
                            finish(dev.projecthim.him.memory.Outcome.APPROACHED);
                            return;
                        }
                    }
                }
            }
            if (tick < nextSoundAt) {
                return;
            }
            if (hitsLeft > 0) {
                for (Digger d : all) {
                    for (Player p : d.listeners) {
                        if (p.isOnline()) {
                            SoundCues.dig(p, d.block, 0.9f);
                        }
                    }
                }
                hitsLeft--;
                nextSoundAt = tick + 4 + ctx.him().random().nextInt(3);
                return;
            }
            // End of a burst: the block "breaks".
            for (Digger d : all) {
                for (Player p : d.listeners) {
                    if (p.isOnline()) {
                        SoundCues.breakSound(p, d.block, 0.9f);
                    }
                }
            }
            burst++;
            if (burst >= bursts) {
                finish(dev.projecthim.him.memory.Outcome.UNNOTICED);
                return;
            }
            for (Digger d : all) {
                d.distance = Math.max(5, d.distance - 3.5);
                place(d);
            }
            nextSoundAt = tick + 40 + ctx.him().random().nextInt(50);
            hitsLeft = 5 + ctx.him().random().nextInt(4);
        }

        @Override
        protected int maxTicks() {
            return 20 * 45;
        }
    }
}
