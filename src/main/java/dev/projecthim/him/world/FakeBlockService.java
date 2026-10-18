package dev.projecthim.him.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Client side only block changes ("hallucinations"). The server world is never touched.
 *
 * <p>Fake blocks can desynchronise collision: a fake solid block is not solid on the server, and a
 * fake air block still is. The service therefore only allows fake <em>solid</em> blocks away from the
 * player and reverts them as soon as the player comes close, reverts anything the player interacts
 * with, forgets entries when the real block changes, and reverts everything when an event ends, the
 * player changes world, or the plugin stops.
 */
public final class FakeBlockService {

    public record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private record Fake(BlockKey key, boolean solid, long expiresAt, String owner) {
    }

    /** Minimum distance between a player and a fake solid block. */
    public static final double SOLID_MIN_DISTANCE = 3.0;

    private final Map<UUID, Map<BlockKey, Fake>> fakes = new HashMap<>();
    private final List<Damage> damages = new ArrayList<>();
    private boolean enabled = true;

    private record Damage(UUID player, Location location, long expiresAt, String owner) {
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            revertAll();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Shows {@code data} at {@code block} to one player only.
     *
     * @return false if refused (disabled, unsafe distance for a solid block, or different world)
     */
    public boolean show(Player player, Block block, BlockData data, long durationTicks, long tick, String owner) {
        if (!enabled || player.getWorld() != block.getWorld()) {
            return false;
        }
        boolean solid = data.getMaterial().isSolid();
        if (solid && player.getLocation().distance(block.getLocation().add(0.5, 0.5, 0.5)) < SOLID_MIN_DISTANCE) {
            return false;
        }
        player.sendBlockChange(block.getLocation(), data);
        BlockKey key = BlockKey.of(block);
        fakes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(key, new Fake(key, solid, tick + durationTicks, owner));
        return true;
    }

    /** Shows a fake sign with text. The sign block itself is a fake block of the given data. */
    public boolean showSign(Player player, Block block, BlockData signData, List<Component> lines, long durationTicks,
                            long tick, String owner) {
        if (!show(player, block, signData, durationTicks, tick, owner)) {
            return false;
        }
        if (signData.createBlockState() instanceof Sign sign) {
            SignSide front = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) {
                front.line(i, i < lines.size() ? lines.get(i) : Component.empty());
            }
            front.setColor(DyeColor.BLACK);
            player.sendBlockUpdate(block.getLocation(), sign);
        }
        return true;
    }

    /** Shows a breaking animation on a block (no world change). Progress 0..1. */
    public void crack(Player player, Location block, float progress, int sourceId, long durationTicks, long tick,
                      String owner) {
        if (!enabled) {
            return;
        }
        player.sendBlockDamage(block, Math.max(0f, Math.min(1f, progress)), sourceId);
        damages.removeIf(d -> d.player.equals(player.getUniqueId()) && d.location.equals(block));
        damages.add(new Damage(player.getUniqueId(), block.clone(), tick + durationTicks, owner));
    }

    public boolean isFake(Player player, Block block) {
        Map<BlockKey, Fake> map = fakes.get(player.getUniqueId());
        return map != null && map.containsKey(BlockKey.of(block));
    }

    /** Called every few ticks: expiry and collision safety. */
    public void tick(long tick) {
        for (Iterator<Map.Entry<UUID, Map<BlockKey, Fake>>> it = fakes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<BlockKey, Fake>> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            Location pl = player.getLocation();
            for (Iterator<Fake> fi = entry.getValue().values().iterator(); fi.hasNext(); ) {
                Fake f = fi.next();
                boolean expired = tick >= f.expiresAt;
                boolean wrongWorld = !player.getWorld().getUID().equals(f.key.world());
                boolean tooClose = !wrongWorld && f.solid
                        && distanceSq(pl, f.key) < (SOLID_MIN_DISTANCE - 0.5) * (SOLID_MIN_DISTANCE - 0.5);
                if (expired || wrongWorld || tooClose) {
                    if (!wrongWorld) {
                        resend(player, f.key);
                    }
                    fi.remove();
                }
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        for (Iterator<Damage> it = damages.iterator(); it.hasNext(); ) {
            Damage d = it.next();
            if (tick >= d.expiresAt) {
                Player p = Bukkit.getPlayer(d.player);
                if (p != null && p.getWorld() == d.location.getWorld()) {
                    p.sendBlockDamage(d.location, 0f, 0);
                }
                it.remove();
            }
        }
    }

    private static double distanceSq(Location l, BlockKey k) {
        double dx = l.getX() - (k.x() + 0.5);
        double dy = l.getY() + 0.9 - (k.y() + 0.5);
        double dz = l.getZ() - (k.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void resend(Player player, BlockKey key) {
        World w = Bukkit.getWorld(key.world());
        if (w == null || player.getWorld() != w || !w.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            return;
        }
        Block real = w.getBlockAt(key.x(), key.y(), key.z());
        player.sendBlockChange(real.getLocation(), real.getBlockData());
        if (real.getState() instanceof org.bukkit.block.Sign) {
            // Restores real sign text after a fake sign.
            real.getState().update(true, false);
        }
    }

    /** Reverts a fake the player touched, so client and server agree again immediately. */
    public void revertIfFake(Player player, Block block) {
        Map<BlockKey, Fake> map = fakes.get(player.getUniqueId());
        if (map != null && map.remove(BlockKey.of(block)) != null) {
            resend(player, BlockKey.of(block));
        }
    }

    /** The real block changed: our record is meaningless now (the server sends the real state). */
    public void forget(Block block) {
        BlockKey key = BlockKey.of(block);
        for (Map<BlockKey, Fake> map : fakes.values()) {
            map.remove(key);
        }
    }

    public void revertOwner(String owner) {
        for (Map.Entry<UUID, Map<BlockKey, Fake>> e : fakes.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            for (Iterator<Fake> it = e.getValue().values().iterator(); it.hasNext(); ) {
                Fake f = it.next();
                if (f.owner.equals(owner)) {
                    if (p != null) {
                        resend(p, f.key);
                    }
                    it.remove();
                }
            }
        }
        for (Iterator<Damage> it = damages.iterator(); it.hasNext(); ) {
            Damage d = it.next();
            if (d.owner.equals(owner)) {
                Player p = Bukkit.getPlayer(d.player);
                if (p != null && p.getWorld() == d.location.getWorld()) {
                    p.sendBlockDamage(d.location, 0f, 0);
                }
                it.remove();
            }
        }
    }

    public void revertPlayer(Player player) {
        Map<BlockKey, Fake> map = fakes.remove(player.getUniqueId());
        if (map != null) {
            for (Fake f : map.values()) {
                resend(player, f.key);
            }
        }
        damages.removeIf(d -> d.player.equals(player.getUniqueId()));
    }

    public void forgetPlayer(UUID id) {
        fakes.remove(id);
        damages.removeIf(d -> d.player.equals(id));
    }

    public void revertAll() {
        for (UUID id : List.copyOf(fakes.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                revertPlayer(p);
            }
        }
        fakes.clear();
        for (Damage d : damages) {
            Player p = Bukkit.getPlayer(d.player);
            if (p != null && p.getWorld() == d.location.getWorld()) {
                p.sendBlockDamage(d.location, 0f, 0);
            }
        }
        damages.clear();
    }

    public int count() {
        int n = damages.size();
        for (Map<BlockKey, Fake> m : fakes.values()) {
            n += m.size();
        }
        return n;
    }
}
