package dev.projecthim.him;

import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.director.DirectorService;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.GlobalBudget;
import dev.projecthim.him.director.HorrorDirector;
import dev.projecthim.him.entity.EntityRegistry;
import dev.projecthim.him.event.EventManager;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.event.impl.EventCatalog;
import dev.projecthim.him.memory.JsonMemoryRepository;
import dev.projecthim.him.memory.MemoryService;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.perf.PerformanceGovernor;
import dev.projecthim.him.platform.EntityTags;
import dev.projecthim.him.platform.SkinService;
import dev.projecthim.him.player.SessionManager;
import dev.projecthim.him.sense.SpotFinder;
import dev.projecthim.him.util.Clock;
import dev.projecthim.him.util.HimLog;
import dev.projecthim.him.world.FakeBlockService;
import dev.projecthim.him.world.ProtectionService;
import dev.projecthim.him.world.WorldChangeJournal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The running HIM: owns every service and drives them from a single server tick task. All state is
 * confined to the server thread; only file IO happens elsewhere.
 */
public final class Him {

    private final Plugin plugin;
    private final HimLog log;
    private final Clock clock = Clock.SYSTEM;
    private final RandomGenerator random = new SplittableRandom();
    private HimConfig config;

    private final EntityTags tags;
    private final EntityRegistry entities;
    private final SpotFinder spots;
    private final FakeBlockService fakes;
    private final MemoryService memory;
    private final WorldChangeJournal journal;
    private final ProtectionService protection;
    private final PerformanceGovernor performance;
    private final SessionManager sessions;
    private final EventManager events;
    private final DirectorService director;
    private final dev.projecthim.him.dialogue.SignService signs;

    private BukkitTask task;
    private long tick;
    private long lastAutosave;
    private boolean paused;
    /** Players whose most recent death was caused by HIM (consumed by the hunt that ends). */
    private final java.util.Set<java.util.UUID> killedByHim = new java.util.HashSet<>();
    /** Gear tier an administrator asked for in a manual hunt, consumed when that hunt starts. */
    private final java.util.Map<java.util.UUID, Integer> manualGearTier = new java.util.HashMap<>();

    public Him(Plugin plugin, HimLog log, HimConfig config) throws IOException {
        this.plugin = plugin;
        this.log = log;
        this.config = config;
        Path data = plugin.getDataFolder().toPath().resolve("data");
        this.tags = new EntityTags(plugin);
        this.entities = new EntityRegistry(plugin, tags, skinFrom(config), log);
        this.spots = new SpotFinder(random);
        this.fakes = new FakeBlockService();
        this.memory = new MemoryService(plugin, new JsonMemoryRepository(data, log.raw()), log, clock);
        this.journal = new WorldChangeJournal(data.resolve("world-changes.json"), memory.ioExecutor(), log);
        this.protection = new ProtectionService(memory, config.worldEdits());
        this.performance = new PerformanceGovernor(config.performance());
        this.sessions = new SessionManager(this);
        this.events = new EventManager(this);
        this.signs = new dev.projecthim.him.dialogue.SignService(this);
        List<HorrorEvent> catalog = EventCatalog.create();
        List<EventSpec> specs = new ArrayList<>();
        for (HorrorEvent e : catalog) {
            events.register(e);
            specs.add(e.spec());
        }
        this.director = new DirectorService(this,
                new HorrorDirector(specs, config.director()),
                new GlobalBudget(config.budget().capacity(), config.budget().refillPerMinute(), clock.now()));
        applyConfig(config);
    }

    private static SkinService skinFrom(HimConfig config) {
        return new SkinService(config.apparitions().skinValue(), config.apparitions().skinSignature());
    }

    public void start() {
        memory.loadWorld();
        memory.world().personality().relax(clock.now(), config.memory().personalityRelaxPerDay());
        journal.load();
        int strays = entities.sweepAllWorlds();
        if (strays > 0) {
            log.info("Removed " + strays + " leftover HIM entities from a previous run.");
        }
        memory.forgetInactive(config.memory().forgetAfterDays());
        for (Player p : Bukkit.getOnlinePlayers()) {
            sessions.join(p);
        }
        lastAutosave = clock.now();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSafely, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        events.cancelAll(Outcome.INTERRUPTED);
        entities.removeAll();
        fakes.revertAll();
        int restored = journal.restoreAllLoaded();
        if (restored > 0) {
            log.info("Restored " + restored + " block(s) changed by HIM.");
        }
        memory.shutdown();
        journal.flushNow();
    }

    /** Applies a freshly validated configuration to every service. */
    public void applyConfig(HimConfig config) {
        this.config = config;
        log.setLevel(config.logLevel());
        entities.setSkin(skinFrom(config));
        protection.configure(config.worldEdits());
        performance.configure(config.performance());
        fakes.setEnabled(config.worldEdits().fakeBlocks());
        director.director().updateSettings(config.director());
        director.budget().configure(config.budget().capacity(), config.budget().refillPerMinute());
        if (!config.enabled()) {
            events.cancelAll(Outcome.INTERRUPTED);
        }
    }

    private void tickSafely() {
        performance.beginTick();
        try {
            runTick();
        } catch (RuntimeException e) {
            log.error("Unexpected error in the HIM tick loop; encounters were stopped to stay safe", e);
            events.cancelAll(Outcome.INTERRUPTED);
        } finally {
            performance.endTick();
        }
    }

    private void runTick() {
        tick++;
        if (tick % 20 == 0) {
            PerformanceGovernor.Level before = performance.level();
            performance.sample();
            if (performance.level() == PerformanceGovernor.Level.CRITICAL && before != PerformanceGovernor.Level.CRITICAL) {
                log.warn("Server is under heavy load (" + performance.reason()
                        + "). HIM is withdrawing until it recovers.");
                events.cancelAll(Outcome.INTERRUPTED);
            }
        }
        sessions.tick(tick);
        if (config.enabled() && !paused) {
            director.tick(tick);
        }
        events.tick(tick);
        if (tick % 5 == 0) {
            fakes.tick(tick);
        }
        if (tick % 40 == 7 && config.enabled() && !paused) {
            signs.tick(tick);
        }
        if (tick % 200 == 0) {
            journal.tick(clock.now());
            entities.pruneInvalid();
        }
        long now = clock.now();
        if (now - lastAutosave >= config.memory().autosaveMinutes() * 60_000L) {
            lastAutosave = now;
            memory.saveDirty();
        }
    }

    public boolean worldAllowed(World world) {
        if (!config.worlds().allowed(world.getName())) {
            return false;
        }
        return switch (world.getEnvironment()) {
            case NETHER -> config.worlds().allowNether();
            case THE_END -> config.worlds().allowEnd();
            default -> true;
        };
    }

    public boolean canSpawnApparition() {
        return entities.activeBodies() < config.apparitions().maxActive()
                && performance.level() != PerformanceGovernor.Level.CRITICAL;
    }

    public boolean huntSlotFree() {
        return config.chase().enabled() && performance.allowNewHunts()
                && events.activeHunts() < config.chase().maxConcurrent();
    }

    /** Called by the death listener before encounters are notified. */
    public void recordDeath(java.util.UUID player, boolean byHim) {
        if (byHim) {
            killedByHim.add(player);
        } else {
            killedByHim.remove(player);
        }
    }

    /** Whether the player's latest death was caused by HIM. Consumes the record. */
    public boolean consumeKilledByHim(java.util.UUID player) {
        return killedByHim.remove(player);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            events.cancelAll(Outcome.INTERRUPTED);
        }
    }

    public boolean paused() {
        return paused;
    }

    public void setManualGearTier(java.util.UUID player, Integer tier) {
        if (tier == null) {
            manualGearTier.remove(player);
        } else {
            manualGearTier.put(player, tier);
        }
    }

    /** The gear tier asked for by /him chase or /him trigger hunt, if any (only once). */
    public Integer takeManualGearTier(java.util.UUID player) {
        return manualGearTier.remove(player);
    }

    public Plugin plugin() {
        return plugin;
    }

    public HimLog log() {
        return log;
    }

    public Clock clock() {
        return clock;
    }

    public RandomGenerator random() {
        return random;
    }

    public HimConfig config() {
        return config;
    }

    public long tick() {
        return tick;
    }

    public EntityRegistry entities() {
        return entities;
    }

    public SpotFinder spots() {
        return spots;
    }

    public FakeBlockService fakes() {
        return fakes;
    }

    public MemoryService memory() {
        return memory;
    }

    public WorldChangeJournal journal() {
        return journal;
    }

    public ProtectionService protection() {
        return protection;
    }

    public PerformanceGovernor performance() {
        return performance;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public EventManager events() {
        return events;
    }

    public dev.projecthim.him.dialogue.SignService signs() {
        return signs;
    }

    public DirectorService director() {
        return director;
    }
}
