package dev.projecthim.him.memory;

import dev.projecthim.him.util.Clock;
import dev.projecthim.him.util.HimLog;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Owns the in-memory copies of player memories and the world state.
 *
 * <p>Threading: all objects are only touched on the server thread. Files are read and written on a
 * single dedicated IO thread; for saving, objects are serialised to JSON on the server thread first
 * so the IO thread never sees a mutable object.
 */
public final class MemoryService {

    private final Plugin plugin;
    private final MemoryRepository repository;
    private final HimLog log;
    private final Clock clock;
    private final ExecutorService io;
    private final Map<UUID, PlayerMemory> online = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Set<UUID> pendingLoads = new HashSet<>();
    private WorldState world;
    private boolean worldDirty;

    public MemoryService(Plugin plugin, MemoryRepository repository, HimLog log, Clock clock) {
        this.plugin = plugin;
        this.repository = repository;
        this.log = log;
        this.clock = clock;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HIM-memory-io");
            t.setDaemon(true);
            return t;
        });
    }

    /** Loads the world state synchronously. Only used during enable, the file is small. */
    public void loadWorld() {
        long now = clock.now();
        SplittableRandom random = new SplittableRandom();
        try {
            world = repository.loadWorld().orElse(null);
        } catch (IOException e) {
            log.error("Could not read world state, starting fresh", e);
            world = null;
        }
        if (world == null) {
            world = WorldState.fresh(random, now);
            worldDirty = true;
            log.info("HIM has awoken with a new personality.");
        }
        world.sanitize(random, now);
    }

    /** The single IO thread, shared with other small persistent stores to keep write ordering. */
    public java.util.concurrent.Executor ioExecutor() {
        return io;
    }

    public WorldState world() {
        return world;
    }

    public void markWorldDirty() {
        worldDirty = true;
    }

    public void resetWorld() {
        world = WorldState.fresh(new SplittableRandom(), clock.now());
        worldDirty = true;
    }

    public PlayerMemory get(UUID id) {
        return online.get(id);
    }

    public boolean isLoaded(UUID id) {
        return online.containsKey(id);
    }

    public Map<UUID, PlayerMemory> loaded() {
        return Map.copyOf(online);
    }

    /**
     * Loads (or creates) a player's memory asynchronously and hands it to {@code onLoaded} on the
     * server thread. If {@code stillWanted} is false by then (player left), the result is dropped.
     */
    public void load(UUID id, String name, java.util.function.Predicate<UUID> stillWanted, Consumer<PlayerMemory> onLoaded) {
        if (online.containsKey(id) || !pendingLoads.add(id)) {
            return;
        }
        io.execute(() -> {
            PlayerMemory memory;
            try {
                memory = repository.loadPlayer(id).orElse(null);
            } catch (IOException e) {
                log.error("Could not read memory of " + name + ", using a fresh one", e);
                memory = null;
            }
            PlayerMemory result = memory;
            runSync(() -> {
                pendingLoads.remove(id);
                if (!stillWanted.test(id)) {
                    return;
                }
                PlayerMemory m = result != null ? result : new PlayerMemory(id, name, clock.now());
                m.sanitize();
                online.put(id, m);
                dirty.add(id);
                onLoaded.accept(m);
            });
        });
    }

    /** Reads a memory for inspection without caching it (offline players). */
    public CompletableFuture<Optional<PlayerMemory>> peek(UUID id) {
        PlayerMemory cached = online.get(id);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        CompletableFuture<Optional<PlayerMemory>> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                Optional<PlayerMemory> m = repository.loadPlayer(id);
                runSync(() -> future.complete(m));
            } catch (IOException e) {
                runSync(() -> future.completeExceptionally(e));
            }
        });
        return future;
    }

    public void markDirty(UUID id) {
        if (online.containsKey(id)) {
            dirty.add(id);
        }
    }

    /** Saves and forgets a player that left. */
    public void unload(UUID id) {
        PlayerMemory m = online.remove(id);
        dirty.remove(id);
        if (m != null) {
            String json = MemoryCodec.encode(m);
            io.execute(() -> write(id, json));
        }
    }

    /**
     * Forgets a player completely. The cached copy is dropped and the file deletion is queued on the
     * IO thread, so a subsequent {@link #load} is guaranteed to start from a fresh memory.
     */
    public void delete(UUID id) {
        online.remove(id);
        dirty.remove(id);
        io.execute(() -> {
            try {
                repository.deletePlayer(id);
            } catch (IOException e) {
                log.error("Could not delete memory of " + id, e);
            }
        });
    }

    /** Serialises dirty entries now and writes them in the background. */
    public void saveDirty() {
        for (UUID id : List.copyOf(dirty)) {
            PlayerMemory m = online.get(id);
            if (m != null) {
                String json = MemoryCodec.encode(m);
                io.execute(() -> write(id, json));
            }
        }
        dirty.clear();
        if (worldDirty && world != null) {
            String json = MemoryCodec.encode(world);
            io.execute(() -> writeWorld(json));
            worldDirty = false;
        }
    }

    /** Final save on shutdown: writes everything and waits for the IO thread to finish. */
    public void shutdown() {
        for (Map.Entry<UUID, PlayerMemory> e : online.entrySet()) {
            String json = MemoryCodec.encode(e.getValue());
            UUID id = e.getKey();
            io.execute(() -> write(id, json));
        }
        if (world != null) {
            String json = MemoryCodec.encode(world);
            io.execute(() -> writeWorld(json));
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("Memory IO did not finish within 15 seconds; some data may not be saved.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        online.clear();
    }

    /** Deletes memories of players not seen for the given number of days. Runs in the background. */
    public void forgetInactive(double days) {
        if (days <= 0) {
            return;
        }
        long cutoff = clock.now() - (long) (days * 86_400_000L);
        Set<UUID> onlineNow = Set.copyOf(online.keySet());
        io.execute(() -> {
            int removed = 0;
            try {
                for (UUID id : repository.listPlayers()) {
                    long modified = repository.lastModified(id);
                    if (modified > 0 && modified < cutoff && !onlineNow.contains(id)) {
                        repository.deletePlayer(id);
                        removed++;
                    }
                }
            } catch (IOException e) {
                log.error("Could not clean up old memories", e);
            }
            if (removed > 0) {
                int n = removed;
                log.info("Forgot " + n + " players inactive for more than " + days + " days.");
            }
        });
    }

    private void write(UUID id, String json) {
        try {
            repository.savePlayer(id, json);
        } catch (IOException e) {
            log.error("Could not save memory of " + id, e);
        }
    }

    private void writeWorld(String json) {
        try {
            repository.saveWorld(json);
        } catch (IOException e) {
            log.error("Could not save world state", e);
        }
    }

    private void runSync(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }
}
