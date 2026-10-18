package dev.projecthim.him.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.projecthim.him.util.HimLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Every real block HIM changes is recorded here before it is changed, and restored later.
 *
 * <p>Restoration only happens when nobody is close enough to watch it, only if the block is still
 * exactly what HIM placed (a player who built or mined there wins), and survives restarts because
 * the journal is persisted. On shutdown everything in loaded chunks is restored immediately.
 */
public final class WorldChangeJournal {

    /** A single recorded block change. Block data is stored as its string form. */
    public record Change(String world, int x, int y, int z, String original, String placed, long revertAt,
                         String reason) {
    }

    private static final Gson GSON = new Gson();
    private static final double WATCH_RADIUS = 40;

    private final Path file;
    private final Executor io;
    private final HimLog log;
    private final List<Change> changes = new ArrayList<>();
    private boolean dirty;

    public WorldChangeJournal(Path file, Executor io, HimLog log) {
        this.file = file;
        this.io = io;
        this.log = log;
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<Change> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Change>>() { }.getType());
            if (loaded != null) {
                loaded.removeIf(c -> c == null || c.world() == null || c.original() == null || c.placed() == null);
                changes.addAll(loaded);
            }
            if (!changes.isEmpty()) {
                log.info("World change journal: " + changes.size() + " block(s) waiting to be restored.");
            }
        } catch (IOException | RuntimeException e) {
            log.error("Could not read the world change journal " + file.getFileName()
                    + ". Pending restorations from the previous run are lost.", e);
        }
    }

    /**
     * Changes a block and records how to undo it.
     *
     * @return false if the block is already journaled (never stack changes on the same block)
     */
    public boolean apply(Block block, BlockData data, long revertAt, String reason) {
        String world = block.getWorld().getName();
        for (Change c : changes) {
            if (c.world().equals(world) && c.x() == block.getX() && c.y() == block.getY() && c.z() == block.getZ()) {
                return false;
            }
        }
        changes.add(new Change(world, block.getX(), block.getY(), block.getZ(), block.getBlockData().getAsString(),
                data.getAsString(), revertAt, reason));
        block.setBlockData(data, false);
        dirty = true;
        return true;
    }

    /** Restores due changes that nobody is watching. */
    public void tick(long now) {
        boolean changed = false;
        for (Iterator<Change> it = changes.iterator(); it.hasNext(); ) {
            Change c = it.next();
            if (now < c.revertAt()) {
                continue;
            }
            World w = Bukkit.getWorld(c.world());
            if (w == null || !w.isChunkLoaded(c.x() >> 4, c.z() >> 4) || watched(w, c)) {
                continue;
            }
            if (restore(w, c)) {
                changed = true;
            }
            it.remove();
            changed = true;
        }
        if (changed) {
            dirty = true;
        }
        flush();
    }

    private static boolean watched(World w, Change c) {
        double r2 = WATCH_RADIUS * WATCH_RADIUS;
        for (Player p : w.getPlayers()) {
            double dx = p.getX() - c.x();
            double dz = p.getZ() - c.z();
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }

    private boolean restore(World w, Change c) {
        Block b = w.getBlockAt(c.x(), c.y(), c.z());
        try {
            BlockData placed = Bukkit.createBlockData(c.placed());
            if (!b.getBlockData().matches(placed)) {
                // Someone changed the block since; leave their change alone.
                return false;
            }
            b.setBlockData(Bukkit.createBlockData(c.original()), false);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Skipping unparseable journal entry at " + c.world() + " " + c.x() + "," + c.y() + "," + c.z());
            return false;
        }
    }

    /** Restores everything in loaded chunks regardless of watchers. Used on shutdown. */
    public int restoreAllLoaded() {
        int restored = 0;
        for (Iterator<Change> it = changes.iterator(); it.hasNext(); ) {
            Change c = it.next();
            World w = Bukkit.getWorld(c.world());
            if (w != null && w.isChunkLoaded(c.x() >> 4, c.z() >> 4)) {
                if (restore(w, c)) {
                    restored++;
                }
                it.remove();
                dirty = true;
            }
        }
        return restored;
    }

    /** Restores the change recorded for one block right away, if any. Returns whether it was restored. */
    public boolean restoreNow(Block block) {
        String world = block.getWorld().getName();
        for (Iterator<Change> it = changes.iterator(); it.hasNext(); ) {
            Change c = it.next();
            if (c.world().equals(world) && c.x() == block.getX() && c.y() == block.getY() && c.z() == block.getZ()) {
                it.remove();
                dirty = true;
                return restore(block.getWorld(), c);
            }
        }
        return false;
    }

    /** Marks every change as due now (admin cleanup). */
    public void expediteAll() {
        changes.replaceAll(c -> new Change(c.world(), c.x(), c.y(), c.z(), c.original(), c.placed(), 0, c.reason()));
        dirty = true;
    }

    public boolean contains(Block block) {
        String world = block.getWorld().getName();
        for (Change c : changes) {
            if (c.world().equals(world) && c.x() == block.getX() && c.y() == block.getY() && c.z() == block.getZ()) {
                return true;
            }
        }
        return false;
    }

    public int pending() {
        return changes.size();
    }

    /** Persists asynchronously if anything changed. */
    public void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        String json = GSON.toJson(List.copyOf(changes));
        io.execute(() -> write(json));
    }

    /** Persists synchronously (shutdown). */
    public void flushNow() {
        write(GSON.toJson(List.copyOf(changes)));
        dirty = false;
    }

    private void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not write the world change journal", e);
        }
    }
}
