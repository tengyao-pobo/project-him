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
import dev.projecthim.him.platform.SoundCues;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;

/**
 * Knocking on a door of the building the player is in. Three knocks, a pause, three slower knocks.
 * It stops when they walk up to the door. Subverted (and only if door interaction is allowed): when
 * they stand at the door, it slowly opens by itself, and there is nobody outside.
 */
final class DoorKnockEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("door_knock", Intent.PRESENCE)
            .stage(Stage.NOTICED)
            .intensity(0.4)
            .cooldownMinutes(45)
            .weight(1.3)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .requires(PlayerContext::sheltered)
            .fit(c -> (c.night() ? 1.5 : 0.8) * (c.atBase(48) ? 1.3 : 1.0))
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        Block door = findDoor(ctx.target(), 14);
        return door == null ? null : new Instance(ctx, door);
    }

    /** Lower half of the nearest closed wooden door around the player. */
    static Block findDoor(Player player, int radius) {
        Location l = player.getLocation();
        World w = l.getWorld();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = l.getBlockX() + dx;
                int z = l.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -3; dy <= 3; dy++) {
                    int y = l.getBlockY() + dy;
                    Material m = w.getType(x, y, z);
                    if (!Tag.WOODEN_DOORS.isTagged(m)) {
                        continue;
                    }
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.BOTTOM && !door.isOpen()) {
                        double d = b.getLocation().distanceSquared(l);
                        if (d > 4 && d < bestD) {
                            bestD = d;
                            best = b;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static final class Instance extends AudioInstance {
        private final Block door;
        private final List<Player> listeners;
        private int knock;
        private long nextAt;
        private boolean opened;
        private long openedAt;
        private long nearSince = -1;

        Instance(EventContext ctx, Block door) {
            super(ctx);
            this.door = door;
            this.listeners = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(ctx.target());
        }

        @Override
        protected boolean start() {
            setWhere(door.getLocation());
            nextAt = ctx.him().tick() + 10;
            return true;
        }

        private Location source() {
            return door.getLocation().add(0.5, 1.0, 0.5);
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            observe(p, source());
            double d = p.getLocation().distance(source());
            if (opened) {
                if (tick - openedAt > 50) {
                    closeDoor();
                    finish(Outcome.APPROACHED);
                }
                return;
            }
            if (d < 3.2) {
                if (nearSince < 0) {
                    nearSince = tick;
                }
                setOutcome(p.getUniqueId(), Outcome.APPROACHED);
                if (ctx.subvert() && ctx.him().config().worldEdits().doorInteraction() && tick - nearSince > 30) {
                    openDoor(tick);
                } else if (!ctx.subvert()) {
                    finish(Outcome.APPROACHED);
                }
                return;
            }
            nearSince = -1;
            if (tick < nextAt) {
                return;
            }
            if (knock >= 6) {
                if (!ctx.subvert()) {
                    finish(Outcome.UNNOTICED);
                } else if (tick > nextAt + 20 * 20) {
                    finish(Outcome.UNNOTICED);
                }
                return;
            }
            Sound hit = door.getBlockData().getSoundGroup().getHitSound();
            SoundCues.play(listeners, source(), hit, SoundCategory.BLOCKS, 1.0f, 0.7f);
            knock++;
            if (knock == 3) {
                nextAt = tick + 50 + ctx.him().random().nextInt(30);
            } else {
                nextAt = tick + (knock < 3 ? 6 : 10);
            }
        }

        private void openDoor(long tick) {
            if (door.getBlockData() instanceof Door data && !data.isOpen()) {
                data.setOpen(true);
                door.setBlockData(data);
                SoundCues.play(listeners, source(), Sound.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, 0.8f, 0.6f);
                opened = true;
                openedAt = tick;
            } else {
                finish(Outcome.APPROACHED);
            }
        }

        private void closeDoor() {
            if (opened && door.getBlockData() instanceof Door data && data.isOpen()) {
                data.setOpen(false);
                door.setBlockData(data);
                SoundCues.play(listeners, source(), Sound.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 0.8f, 0.6f);
            }
            opened = false;
        }

        @Override
        protected void cleanup() {
            closeDoor();
        }

        @Override
        protected int maxTicks() {
            return 20 * 60;
        }
    }
}
