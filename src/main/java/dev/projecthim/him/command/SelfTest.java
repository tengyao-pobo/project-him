package dev.projecthim.him.command;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.projecthim.him.Him;
import dev.projecthim.him.director.DirectorDecision;
import dev.projecthim.him.director.DirectorInput;
import dev.projecthim.him.director.GlobalBudget;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.TensionTracker;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.entity.HimCarrier;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.sense.Sight;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Server side self test, runnable from the console. It exercises the parts that need a real Paper
 * server: entity creation and configuration, the skin profile, line of sight against real blocks,
 * carrier navigation and speed, obstacle handling, the world change journal, and director speed.
 * It runs in a dedicated flat world named {@value #WORLD} so no gameplay world is touched.
 *
 * <p>What it cannot test: anything that needs a connected client (whether a client actually renders
 * the skin, private visibility on screen, sounds).
 */
final class SelfTest {

    static final String WORLD = "him_selftest";

    private final Him him;
    private final CommandSender sender;
    private final List<String> failures = new ArrayList<>();
    private int passed;
    private World world;
    private int y;
    private BukkitTask task;
    private int step;
    private long stepStart;
    private HimCarrier carrier;
    private Location carrierStart;

    SelfTest(Him him, CommandSender sender) {
        this.him = him;
        this.sender = sender;
    }

    void start() {
        say("開始自我測試（使用測試世界 " + WORLD + "）…");
        try {
            staticChecks();
            prepareWorld();
            bodyChecks();
            sightChecks();
            journalChecks();
            directorBenchmark();
        } catch (RuntimeException e) {
            fail("同步測試發生例外：" + e);
            him.log().error("Self test crashed", e);
        }
        if (world == null) {
            finish();
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(him.plugin(), this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ synchronous checks

    private void staticChecks() {
        int events = him.events().all().size();
        check(events >= 20, "已註冊事件數量 " + events);
        long ids = him.events().all().stream().map(e -> e.id()).distinct().count();
        check(ids == events, "事件 ID 不重複");
        check(him.config().warnings().isEmpty(), "設定檔沒有警告（" + him.config().warnings().size() + " 個）");
    }

    private void prepareWorld() {
        world = Bukkit.getWorld(WORLD);
        if (world == null) {
            world = new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false).createWorld();
        }
        check(world != null, "建立 / 載入測試世界");
        if (world == null) {
            return;
        }
        world.getChunkAt(0, 0).load(true);
        for (int cx = -1; cx <= 4; cx++) {
            for (int cz = -2; cz <= 1; cz++) {
                world.getChunkAt(cx, cz).load(true);
            }
        }
        y = world.getHighestBlockYAt(0, 0) + 1;
        // A clean arena: solid floor, empty air above.
        for (int x = -4; x <= 60; x++) {
            for (int z = -12; z <= 12; z++) {
                world.getBlockAt(x, y - 1, z).setType(Material.STONE, false);
                for (int dy = 0; dy <= 5; dy++) {
                    world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
                }
            }
        }
        world.setTime(6000);
    }

    private void bodyChecks() {
        if (world == null) {
            return;
        }
        Location at = new Location(world, 2.5, y, 0.5);
        HimBody body = him.entities().spawnBody(at);
        check(body != null && body.valid(), "生成 HIM 本體（Mannequin）");
        if (body == null) {
            return;
        }
        var m = body.entity();
        check(m.getType() == EntityType.MANNEQUIN, "本體實體類型為 MANNEQUIN");
        check(!m.isVisibleByDefault(), "本體預設對所有玩家隱藏");
        check(!m.isPersistent(), "本體不會被存入世界檔");
        check(!m.hasGravity() && m.isImmovable(), "本體無重力且不可推動");
        check(him.entities().isOurs(m), "本體帶有 HIM 標記");
        boolean hasTextures = false;
        boolean signed = false;
        for (ProfileProperty p : m.getProfile().properties()) {
            if (p.getName().equals("textures")) {
                hasTextures = true;
                signed = p.isSigned();
            }
        }
        boolean wantSkin = !him.config().apparitions().skinValue().isBlank();
        check(!wantSkin || hasTextures, "本體 Profile 帶有皮膚 textures 屬性");
        check(!wantSkin || signed, "皮膚屬性帶有 Mojang 簽名");
        // Static profiles are used by the client as-is; dynamic ones would be looked up online by id.
        check(!wantSkin || !m.getProfile().dynamic(), "皮膚 Profile 為靜態（客戶端直接使用簽名貼圖）");
        check(m.getDescription() == null && m.customName() == null, "本體沒有名稱與描述（不顯示名牌）");
        Location moved = at.clone().add(3, 0, 0);
        moved.setYaw(90);
        body.moveTo(moved);
        check(body.location().distance(moved) < 0.01, "本體可移動到指定位置");
        Location gazeTarget = moved.clone().add(0, 1.62, 10);
        body.lookAt(gazeTarget, true);
        check(Math.abs(body.location().getYaw()) < 2, "本體可轉向凝視目標（yaw=" + fmt(body.location().getYaw()) + "）");
        UUID id = m.getUniqueId();
        him.entities().remove(body);
        check(Bukkit.getEntity(id) == null || !m.isValid(), "移除本體後不留殘影");
    }

    private void sightChecks() {
        if (world == null) {
            return;
        }
        for (int z = -3; z <= 3; z++) {
            for (int dy = 0; dy <= 3; dy++) {
                world.getBlockAt(10, y + dy, z).setType(Material.GLASS, false);
                world.getBlockAt(20, y + dy, z).setType(Material.STONE, false);
                world.getBlockAt(30, y + dy, z).setType(Material.OAK_LEAVES, false);
            }
        }
        double eye = y + 1.62;
        double glass = Sight.visibility(world, new org.bukkit.util.Vector(5.5, eye, 0.5), new org.bukkit.util.Vector(15.5, eye, 0.5));
        double stone = Sight.visibility(world, new org.bukkit.util.Vector(15.5, eye, 0.5), new org.bukkit.util.Vector(25.5, eye, 0.5));
        double leaves = Sight.visibility(world, new org.bukkit.util.Vector(25.5, eye, 0.5), new org.bukkit.util.Vector(35.5, eye, 0.5));
        double open = Sight.visibility(world, new org.bukkit.util.Vector(40.5, eye, 0.5), new org.bukkit.util.Vector(55.5, eye, 0.5));
        check(glass > 0.9, "視線可穿透玻璃（" + fmt(glass) + "）");
        check(stone == 0, "視線被石頭阻擋（" + fmt(stone) + "）");
        check(leaves > 0.3 && leaves < 0.9, "樹葉只部分遮擋視線（" + fmt(leaves) + "）");
        check(open == 1.0, "空曠處視線完全清楚（" + fmt(open) + "）");
        // Clear the walls again for the navigation tests.
        for (int z = -3; z <= 3; z++) {
            for (int dy = 0; dy <= 3; dy++) {
                world.getBlockAt(10, y + dy, z).setType(Material.AIR, false);
                world.getBlockAt(20, y + dy, z).setType(Material.AIR, false);
                world.getBlockAt(30, y + dy, z).setType(Material.AIR, false);
            }
        }
    }

    private void journalChecks() {
        if (world == null) {
            return;
        }
        var block = world.getBlockAt(0, y - 1, 10);
        block.setType(Material.STONE, false);
        boolean applied = him.journal().apply(block, Material.AIR.createBlockData(), 0, "selftest");
        check(applied && block.getType() == Material.AIR, "世界修改日誌：修改方塊");
        check(!him.journal().apply(block, Material.DIRT.createBlockData(), 0, "selftest"), "同一方塊不會重複修改");
        boolean restored = him.journal().restoreNow(block);
        check(restored && block.getType() == Material.STONE, "世界修改日誌：還原方塊");

        // The normal path: a due change is restored by the periodic tick once nobody is near.
        var due = world.getBlockAt(0, y - 1, 11);
        due.setType(Material.STONE, false);
        him.journal().apply(due, Material.AIR.createBlockData(), him.clock().now() - 1, "selftest");
        boolean watched = world.getPlayers().stream().anyMatch(p -> p.getLocation().distance(due.getLocation()) < 48);
        him.journal().tick(him.clock().now());
        if (watched) {
            check(due.getType() == Material.AIR, "世界修改日誌：有玩家在附近時不還原");
            him.journal().restoreNow(due);
        } else {
            check(due.getType() == Material.STONE, "世界修改日誌：無人在附近時自動還原");
        }
    }

    private void directorBenchmark() {
        var director = him.director().director();
        SplittableRandom random = new SplittableRandom(42);
        GlobalBudget budget = new GlobalBudget(1_000_000, 1_000_000, 0);
        PlayerMemory memory = new PlayerMemory(UUID.randomUUID(), "selftest", 0);
        memory.addPlayTime(400 * 60, 0);
        memory.setStageOverride(4);
        Map<String, Integer> picks = new HashMap<>();
        int n = 20_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long now = 10_000_000L + i * 1_000L;
            TensionTracker tension = new TensionTracker(0, random);
            // Random build-up state so the whole intensity range is exercised.
            tension.onEvent(random.nextDouble() * 0.7, now - 200_000);
            PlayerContext ctx = PlayerContext.builder(memory.id(), "world")
                    .night(random.nextBoolean()).underground(random.nextInt(3) == 0)
                    .sheltered(random.nextInt(4) == 0).thundering(random.nextInt(10) == 0)
                    .light(random.nextInt(16)).nearbyPlayers(random.nextInt(3)).speed(random.nextDouble() * 5)
                    .build();
            DirectorInput in = new DirectorInput(ctx, memory, him.memory().world(), tension, Trigger.PERIODIC, now, 1.0,
                    true, true, true, 600);
            if (director.decide(in, budget, random) instanceof DirectorDecision.Act act) {
                picks.merge(act.spec().id(), 1, Integer::sum);
            }
        }
        double micros = (System.nanoTime() - start) / 1000.0 / n;
        check(micros < 200, "導演決策速度 " + fmt(micros) + " µs/次");
        check(picks.size() >= 12, "導演在各種情境下會選擇多樣的事件（" + picks.size() + " 種）");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(picks.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        say("  事件分佈前五：" + top.subList(0, Math.min(5, top.size())));
    }

    // ------------------------------------------------------------------ tick driven checks

    private void tick() {
        long t = him.tick();
        try {
            switch (step) {
                case 0 -> {
                    carrierStart = new Location(world, 2.5, y, 0.5);
                    carrier = him.entities().spawnCarrier(carrierStart);
                    check(carrier != null && carrier.valid(), "建立虛擬導航載體");
                    if (carrier == null) {
                        step = 99;
                        return;
                    }
                    carrier.setSpeed(5.0);
                    boolean planned = carrier.navigate(new Location(world, 55.5, y, 0.5), t, 1000);
                    check(planned, "載體規劃出路徑（" + carrier.pathSize() + " 個節點）");
                    stepStart = t;
                    step = 1;
                }
                case 1 -> {
                    carrier.step();
                    if (t - stepStart >= 60) {
                        double moved = carrier.location().distance(carrierStart);
                        double speed = moved / ((t - stepStart) / 20.0);
                        check(speed > 4.5 && speed < 5.5, "載體路徑移動速度：目標 5.0，實測 " + fmt(speed) + " 格/秒");
                        carrier.teleport(carrierStart);
                        carrier.setSpeed(3.0);
                        carrier.navigate(new Location(world, 55.5, y, 0.5), t, 1000);
                        stepStart = t;
                        step = 2;
                    }
                }
                case 2 -> {
                    carrier.step();
                    if (t - stepStart >= 60) {
                        double moved = carrier.location().distance(carrierStart);
                        double speed = moved / ((t - stepStart) / 20.0);
                        check(speed > 2.7 && speed < 3.3, "載體路徑移動速度：目標 3.0，實測 " + fmt(speed) + " 格/秒");
                        carrier.stop();
                        buildObstacles();
                        carrier.teleport(carrierStart);
                        carrier.setSpeed(5.0);
                        stepStart = t;
                        step = 3;
                    }
                }
                case 3 -> {
                    Location goal = new Location(world, 40.5, y + 1, 0.5);
                    carrier.navigate(goal, t, 10);
                    carrier.step();
                    if (carrier.location().distance(goal) < 2.0) {
                        check(true, "載體繞過牆壁並登上台階（" + (t - stepStart) + " tick）");
                        step = 99;
                    } else if (t - stepStart > 400) {
                        check(false, "載體繞過牆壁並登上台階（逾時，停在 " + carrier.location().toVector() + "）");
                        step = 99;
                    }
                }
                default -> {
                    him.entities().remove(carrier);
                    task.cancel();
                    finish();
                }
            }
        } catch (RuntimeException e) {
            fail("非同步測試發生例外：" + e);
            him.log().error("Self test crashed", e);
            him.entities().remove(carrier);
            task.cancel();
            finish();
        }
    }

    /** A wall across the path with a single gap, then a one block step up. */
    private void buildObstacles() {
        for (int z = -12; z <= 12; z++) {
            if (z == 6) {
                continue;
            }
            for (int dy = 0; dy <= 2; dy++) {
                world.getBlockAt(15, y + dy, z).setType(Material.STONE, false);
            }
        }
        for (int x = 30; x <= 45; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, y, z).setType(Material.STONE, false);
            }
        }
    }

    // ------------------------------------------------------------------ reporting

    private void check(boolean ok, String name) {
        if (ok) {
            passed++;
            sender.sendMessage(Component.text("  ✔ " + name, NamedTextColor.GREEN));
            logIfRemote("PASS " + name);
        } else {
            fail(name);
        }
    }

    private void fail(String name) {
        failures.add(name);
        sender.sendMessage(Component.text("  ✘ " + name, NamedTextColor.RED));
        logIfRemote("FAIL " + name);
    }

    /** Mirrors results into the server log unless the sender already is the console. */
    private void logIfRemote(String line) {
        if (sender != Bukkit.getConsoleSender()) {
            him.log().info("[selftest] " + line);
        }
    }

    private void say(String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private void finish() {
        String summary = "自我測試完成：" + passed + " 項通過，" + failures.size() + " 項失敗。";
        sender.sendMessage(Component.text(summary, failures.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED));
        him.log().info("[selftest] " + summary + (failures.isEmpty() ? "" : " Failed: " + failures));
        say("（需要客戶端的項目，例如皮膚實際顯示、私人可見性、音效，請依 TESTING.md 實機測試。）");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
