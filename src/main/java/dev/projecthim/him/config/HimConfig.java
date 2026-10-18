package dev.projecthim.him.config;

import dev.projecthim.him.chase.ChaseSettings;
import dev.projecthim.him.director.DirectorSettings;
import dev.projecthim.him.director.Stage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, validated view of config.yml. Build with {@link #load(ConfigurationSection, Set)}. */
public record HimConfig(
        boolean enabled,
        LogLevel logLevel,
        Worlds worlds,
        Players players,
        DirectorSettings director,
        Budget budget,
        double groupRadius,
        Apparitions apparitions,
        Chase chase,
        WorldEdits worldEdits,
        Multiplayer multiplayer,
        Memory memory,
        Performance performance,
        Messages messages,
        Impersonation impersonation,
        Signs signs,
        List<String> warnings) {

    public static final int CURRENT_VERSION = 1;

    public enum LogLevel {
        OFF, INFO, DEBUG, TRACE;

        public boolean allows(LogLevel level) {
            return this != OFF && level.ordinal() <= ordinal();
        }
    }

    public enum WorldListMode {
        BLACKLIST, WHITELIST
    }

    public record Worlds(WorldListMode mode, Set<String> list, boolean allowNether, boolean allowEnd) {

        public boolean allowed(String worldName) {
            boolean listed = list.contains(worldName.toLowerCase(Locale.ROOT));
            return mode == WorldListMode.WHITELIST ? listed : !listed;
        }
    }

    public record Players(boolean participateByDefault, boolean respectExemptPermission, double graceMinutes,
                          boolean survivalOnly) {
    }

    public record Budget(double capacity, double refillPerMinute) {
    }

    public record Apparitions(int maxActive, String skinValue, String skinSignature, double maxDistance) {
    }

    public record Chase(boolean enabled, int maxConcurrent, ChaseSettings settings, String deathMessage, Siege siege,
                        Gear gear, Flight flight, Teleport teleport, Combat combat, Weapons weapons, Elite elite) {
    }

    /**
     * How he fights once he is well equipped (adaptive tier 3 = diamond, 4 = netherite).
     *
     * @param minTier          from this tier on he fights like a skilled player (strafing, hit and back off,
     *                         keeping a spear's distance, dodging a drawn bow)
     * @param leap             with a mace: wind-charge leaps and a smash on landing
     * @param speedPotion      drinks one speed potion per hunt when a player keeps running
     * @param speedBoost       how much faster that makes him (keep his run below a sprinting player)
     * @param minions          summons a few monsters that fight with him
     * @param minionMinTier    from this tier on
     * @param minionBelowHealth when his health falls below this fraction
     * @param minionCount      how many
     * @param minionTypes      entity types, e.g. ZOMBIE, SKELETON, SPIDER
     * @param bossBarMinTier   from this tier on the hunted see his health as a boss bar (0 = never)
     */
    public record Elite(int minTier, boolean leap, boolean speedPotion, double speedBoost, boolean minions,
                        int minionMinTier, double minionBelowHealth, int minionCount, List<String> minionTypes,
                        int bossBarMinTier) {
    }

    /**
     * Fighting HIM during a hunt.
     *
     * @param killable               false: the old rule, he is driven off after a number of hits
     * @param health                 his health (20 = a player's ten hearts)
     * @param armorReductionPerPiece damage he takes is reduced this much per armour piece he wears
     * @param fleeBelow              below this fraction of health he may retreat instead of fighting on
     * @param fleeChance             chance per hit, once below {@code fleeBelow}, that he retreats
     * @param headDropChance         chance he drops his head when killed
     */
    public record Combat(boolean killable, double health, double armorReductionPerPiece, double fleeBelow,
                         double fleeChance, double headDropChance) {
    }

    /**
     * Weapons besides the one in his hand (adaptive gear decides who meets which).
     *
     * @param potionEffects  allowed effects: SLOWNESS, WEAKNESS, DARKNESS
     */
    public record Weapons(boolean bow, double arrowDamage, int bowCooldownTicks, boolean potions,
                          List<String> potionEffects, int potionCooldownTicks, int maxPotions, boolean spears,
                          boolean maces) {
    }

    /**
     * Teleports (by command or plugin) during a hunt.
     *
     * @param follows        how many times per hunt he follows the player to where they teleported
     * @param blockCommands  whether hunted players cannot teleport by command at all
     */
    public record Teleport(int follows, boolean blockCommands) {
    }

    /**
     * Elytra flight during a hunt.
     *
     * @param escapeTicks     total time in the air (per hunt) after which the player has escaped
     * @param watchFromBelow  whether he may be seen once on the ground ahead, looking up
     */
    public record Flight(int escapeTicks, boolean watchFromBelow) {
    }

    /**
     * What HIM may do when a hunted player is close but unreachable (pillar, sealed hole).
     *
     * @param buildMaterial material name; validated as a known material, checked for solidity at runtime
     */
    public record Siege(boolean breakBlocks, boolean buildUp, int maxBlocks, double maxHardness, String buildMaterial,
                        int ticksPerBlock) {
    }

    public enum GearMode {
        NONE, FIXED, ADAPTIVE
    }

    /**
     * Equipment HIM carries during hunts.
     *
     * @param fixed slot name (main-hand, off-hand, helmet, chestplate, leggings, boots, ranged) to material name
     * @param maxTier         highest adaptive tier (0-4)
     * @param enchantGlint    tier 4 shines with an enchantment glint
     * @param fixedGlint      the fixed loadout shines with an enchantment glint
     * @param maxDamageBonus  cap on the damage a weapon adds
     * @param maxRepelBonus   cap on the armour pieces that count (extra hits to repel him, or damage reduction)
     * @param fixedPotions    the fixed loadout carries splash potions
     */
    public record Gear(GearMode mode, Map<String, String> fixed, boolean weaponAffectsDamage,
                       boolean armorAffectsRepel, int maxTier, boolean enchantGlint, boolean fixedGlint,
                       double maxDamageBonus, int maxRepelBonus, boolean fixedPotions) {
    }

    /**
     * Impersonation of other players.
     *
     * @param minDistanceFromReal the real player must be at least this far from the target (or elsewhere)
     */
    public record Impersonation(boolean enabled, boolean showName, double minDistanceFromReal) {
    }

    /**
     * Talking to HIM through signs.
     *
     * @param minStage           players below this stage get no answers yet
     * @param delayMinMinutes    shortest wait before the answer appears
     * @param delayMaxMinutes    longest wait before the answer appears
     * @param unaddressedChance  chance he reacts to a sign that is not meant for him
     * @param quietMinMinutes    shortest quiet he may grant when asked to leave
     * @param quietMaxMinutes    longest quiet he may grant when asked to leave
     * @param visibleDays        how long the writer keeps seeing his answer on the sign
     */
    public record Signs(boolean enabled, int minStage, double delayMinMinutes, double delayMaxMinutes,
                        double unaddressedChance, double quietMinMinutes, double quietMaxMinutes, double visibleDays) {
    }

    public record WorldEdits(boolean fakeBlocks, boolean realChanges, boolean respectMobGriefing,
                             double revertAfterMinutes, double protectRadius, int builtCellThreshold,
                             boolean doorInteraction) {
    }

    public record Multiplayer(int maxParticipants, boolean fakeChat) {
    }

    public record Memory(double autosaveMinutes, double forgetAfterDays, double heatHalfLifeDays, int heatCapacity,
                         double personalityRelaxPerDay) {

        public long heatHalfLifeMs() {
            return (long) (heatHalfLifeDays * 86_400_000L);
        }
    }

    public record Performance(double msptStrained, double msptCritical, double tpsCritical, double ownBudgetMs) {
    }

    /**
     * Player facing texts. Sign texts use '|' to separate the four sign lines and support
     * {player}, {days}, {x}, {z} placeholders.
     */
    public record Messages(String fakeJoinName, List<String> signTexts, Map<String, String> landmarkTexts) {

        public String landmarkText(String kind) {
            return landmarkTexts.getOrDefault(kind, "");
        }
    }

    /**
     * Parses and validates.
     *
     * @param knownEvents ids of all registered events, used to validate {@code disabled-events}
     * @throws ConfigException with every problem found if the configuration is invalid
     */
    public static HimConfig load(ConfigurationSection root, Set<String> knownEvents) throws ConfigException {
        ConfigReader r = new ConfigReader(root);

        int version = r.integer("config-version", CURRENT_VERSION, 1, 1000);
        if (version > CURRENT_VERSION) {
            r.error("config-version", "設定檔版本 " + version + " 比插件支援的版本 " + CURRENT_VERSION + " 新，請使用對應版本的插件");
        }
        boolean enabled = r.bool("enabled", true);
        LogLevel logLevel = r.enumValue("logging.level", LogLevel.class, LogLevel.INFO);

        Set<String> worldList = new HashSet<>();
        for (String w : r.stringList("worlds.list")) {
            worldList.add(w.toLowerCase(Locale.ROOT));
        }
        Worlds worlds = new Worlds(
                r.enumValue("worlds.mode", WorldListMode.class, WorldListMode.BLACKLIST),
                Set.copyOf(worldList),
                r.bool("worlds.allow-nether", false),
                r.bool("worlds.allow-end", false));
        if (worlds.mode() == WorldListMode.WHITELIST && worlds.list().isEmpty()) {
            r.warn("worlds.list", "白名單模式但清單是空的：HIM 不會在任何世界出現");
        }

        Players players = new Players(
                r.bool("players.participate-by-default", true),
                r.bool("players.respect-exempt-permission", true),
                r.number("players.grace-minutes-after-join", 2, 0, 600),
                r.bool("players.survival-and-adventure-only", true));

        double[] meanGaps = r.numberArray("director.mean-gap-minutes", new double[] {35, 24, 17, 13, 10}, 0.5, 1440);
        double[] stageMinutes = r.numberArray("director.stage-play-minutes", new double[] {0, 15, 45, 120, 240}, 0, 1_000_000);
        double[] stageEncRaw = r.numberArray("director.stage-encounters", new double[] {0, 0, 2, 5, 10}, 0, 100_000);
        int[] stageEncounters = new int[stageEncRaw.length];
        for (int i = 0; i < stageEncRaw.length; i++) {
            stageEncounters[i] = (int) Math.round(stageEncRaw[i]);
        }
        for (int i = 1; i < stageMinutes.length; i++) {
            if (stageMinutes[i] < stageMinutes[i - 1]) {
                r.error("director.stage-play-minutes", "數值必須由小到大排列");
                break;
            }
        }
        Set<String> disabled = new HashSet<>();
        for (String id : r.stringList("director.disabled-events")) {
            String norm = id.trim().toLowerCase(Locale.ROOT);
            if (!knownEvents.contains(norm)) {
                r.warn("director.disabled-events", "未知的事件「" + id + "」，已忽略。可用事件：" + String.join(", ", knownEvents.stream().sorted().toList()));
            } else {
                disabled.add(norm);
            }
        }
        double huntAggression = r.number("chase.aggression", 1.0, 0, 3);
        DirectorSettings director = new DirectorSettings(
                r.number("director.frequency", 1.0, 0, 5),
                r.number("director.intensity", 1.0, 0.1, 2),
                r.bool("chase.enabled", true) ? huntAggression : 0.0,
                meanGaps,
                r.number("director.min-gap-seconds", 150, 10, 86_400),
                r.number("director.hazard-shape", 2.0, 1.0, 5.0),
                stageMinutes,
                stageEncounters,
                Stage.of(r.integer("director.max-stage", 4, 0, 4)),
                disabled,
                (long) (r.number("director.subversion-min-gap-minutes", 45, 1, 10_000) * 60_000L),
                r.number("director.evaluation-interval-seconds", 6, 1, 120),
                r.number("director.event-cooldown-multiplier", 1.0, 0.05, 20));
        Budget budget = new Budget(
                r.number("director.global-budget.capacity", 4.0, 0.5, 100),
                r.number("director.global-budget.refill-per-minute", 0.6, 0.01, 100));
        double groupRadius = r.number("multiplayer.group-radius", 32, 4, 128);

        String skinValue = r.string("apparitions.skin.texture-value", "");
        String skinSignature = r.string("apparitions.skin.texture-signature", "");
        if (skinValue.isBlank()) {
            r.warn("apparitions.skin.texture-value", "沒有設定皮膚，HIM 會以預設外觀出現");
        } else if (skinSignature.isBlank()) {
            r.warn("apparitions.skin.texture-signature", "皮膚沒有 Mojang 簽名，原版客戶端可能拒絕顯示該皮膚");
        }
        Apparitions apparitions = new Apparitions(
                r.integer("apparitions.max-active", 3, 1, 32),
                skinValue.trim(), skinSignature.trim(),
                r.number("apparitions.max-distance", 96, 16, 160));

        ChaseSettings cs = new ChaseSettings(
                r.number("chase.speed.walk", 3.0, 0.5, 10),
                r.number("chase.speed.run", 5.0, 0.5, 12),
                r.number("chase.speed.burst", 6.6, 0.5, 14),
                r.integer("chase.burst-ticks", 26, 0, 200),
                r.integer("chase.burst-cooldown-ticks", 160, 20, 2000),
                r.number("chase.attack-range", 1.9, 1.0, 3.5),
                r.number("chase.damage", 5.0, 0, 100),
                r.integer("chase.attack-cooldown-ticks", 24, 5, 200),
                r.bool("chase.lethal", true),
                (int) (r.number("chase.max-duration-seconds", 100, 10, 900) * 20),
                r.integer("chase.reveal-ticks", 36, 0, 200),
                r.integer("chase.lose-sight-ticks", 70, 10, 600),
                (int) (r.number("chase.search-seconds", 18, 2, 120) * 20),
                r.number("chase.escape-distance", 40, 16, 160),
                r.integer("chase.hits-to-repel", 6, 1, 100),
                r.number("chase.fake-retreat-chance", 0.35, 0, 1),
                r.integer("chase.max-hidden-repositions", 2, 0, 10),
                r.bool("chase.open-doors", true));
        if (cs.runSpeed() < cs.walkSpeed()) {
            r.error("chase.speed.run", "奔跑速度不可低於行走速度");
        }
        if (cs.burstSpeed() < cs.runSpeed()) {
            r.error("chase.speed.burst", "爆發速度不可低於奔跑速度");
        }
        if (cs.runSpeed() > 5.6) {
            r.warn("chase.speed.run", "奔跑速度高於玩家衝刺速度（約 5.6），玩家幾乎不可能逃脫");
        }
        boolean editsMaster = r.bool("world-modification.enabled", true);
        String buildMaterial = r.string("chase.siege.build-material", "NETHERRACK").trim();
        if (org.bukkit.Material.matchMaterial(buildMaterial) == null) {
            r.error("chase.siege.build-material", "未知的方塊「" + buildMaterial + "」");
        }
        Siege siege = new Siege(
                editsMaster && r.bool("chase.siege.break-blocks", true),
                editsMaster && r.bool("chase.siege.build-up", true),
                r.integer("chase.siege.max-blocks", 12, 0, 256),
                r.number("chase.siege.max-hardness", 5.0, 0, 100),
                buildMaterial,
                r.integer("chase.siege.ticks-per-block", 20, 1, 400));
        Map<String, String> fixedGear = new LinkedHashMap<>();
        for (String slot : List.of("main-hand", "off-hand", "helmet", "chestplate", "leggings", "boots", "ranged")) {
            String item = r.string("chase.equipment.fixed." + slot, "").trim();
            if (!item.isEmpty()) {
                if (org.bukkit.Material.matchMaterial(item) == null) {
                    r.error("chase.equipment.fixed." + slot, "未知的物品「" + item + "」");
                } else {
                    fixedGear.put(slot, item);
                }
            }
        }
        Gear gear = new Gear(
                r.enumValue("chase.equipment.mode", GearMode.class, GearMode.ADAPTIVE),
                Map.copyOf(fixedGear),
                r.bool("chase.equipment.weapon-affects-damage", true),
                r.bool("chase.equipment.armor-affects-repel", true),
                r.integer("chase.equipment.max-tier", 4, 0, 4),
                r.bool("chase.equipment.enchant-glint", true),
                r.bool("chase.equipment.fixed.enchant-glint", false),
                r.number("chase.equipment.max-damage-bonus", 2.0, 0, 20),
                r.integer("chase.equipment.max-repel-bonus", 3, 0, 20),
                r.bool("chase.equipment.fixed.potions", false));
        Chase chase = new Chase(r.bool("chase.enabled", true), r.integer("chase.max-concurrent", 1, 0, 16), cs,
                r.string("chase.death-message", "{player} 再也沒有回來。"), siege, gear,
                new Flight((int) (r.number("chase.flight.escape-seconds", 30, 3, 600) * 20),
                        r.bool("chase.flight.watch-from-below", true)),
                new Teleport(r.integer("chase.teleport.follows", 2, 0, 20),
                        r.bool("chase.teleport.block-commands", false)),
                new Combat(r.bool("chase.combat.killable", true),
                        r.number("chase.combat.health", 60, 1, 2000),
                        r.number("chase.combat.armor-reduction-per-piece", 0.08, 0, 0.25),
                        r.number("chase.combat.flee-below", 0.3, 0, 1),
                        r.number("chase.combat.flee-chance", 0.35, 0, 1),
                        r.number("chase.combat.head-drop-chance", 0.05, 0, 1)),
                new Weapons(r.bool("chase.weapons.bow", true),
                        r.number("chase.weapons.arrow-damage", 4.0, 0, 40),
                        r.integer("chase.weapons.bow-cooldown-ticks", 60, 20, 2000),
                        r.bool("chase.weapons.potions", true),
                        potionEffects(r),
                        r.integer("chase.weapons.potion-cooldown-ticks", 300, 40, 6000),
                        r.integer("chase.weapons.max-potions", 3, 0, 20),
                        r.bool("chase.weapons.spears", true),
                        r.bool("chase.weapons.maces", true)),
                new Elite(r.integer("chase.elite.min-tier", 3, 1, 5),
                        r.bool("chase.elite.wind-charge-leap", true),
                        r.bool("chase.elite.speed-potion", true),
                        r.number("chase.elite.speed-boost", 1.1, 1.0, 2.0),
                        r.bool("chase.elite.minions.enabled", true),
                        r.integer("chase.elite.minions.min-tier", 4, 1, 5),
                        r.number("chase.elite.minions.below-health", 0.5, 0, 1),
                        r.integer("chase.elite.minions.count", 3, 0, 8),
                        minionTypes(r),
                        r.integer("chase.elite.boss-bar-min-tier", 4, 0, 5)));
        Impersonation impersonation = new Impersonation(
                r.bool("impersonation.enabled", true),
                r.bool("impersonation.show-name", true),
                r.number("impersonation.min-distance-from-real", 48, 8, 1000));
        Signs signs = new Signs(
                r.bool("signs.enabled", true),
                r.integer("signs.min-stage", 1, 0, 4),
                r.number("signs.reply-delay-minutes.min", 2, 0.1, 1440),
                r.number("signs.reply-delay-minutes.max", 12, 0.1, 1440),
                r.number("signs.unaddressed-chance", 0.2, 0, 1),
                r.number("signs.quiet-minutes.min", 20, 1, 1440),
                r.number("signs.quiet-minutes.max", 60, 1, 1440),
                r.number("signs.visible-days", 3, 0.01, 365));
        if (signs.delayMaxMinutes() < signs.delayMinMinutes()) {
            r.error("signs.reply-delay-minutes.max", "不可小於 min");
        }
        if (signs.quietMaxMinutes() < signs.quietMinMinutes()) {
            r.error("signs.quiet-minutes.max", "不可小於 min");
        }

        boolean editsEnabled = r.bool("world-modification.enabled", true);
        WorldEdits edits = new WorldEdits(
                editsEnabled && r.bool("world-modification.fake-blocks", true),
                editsEnabled && r.bool("world-modification.real-changes", true),
                r.bool("world-modification.respect-mob-griefing", true),
                r.number("world-modification.revert-after-minutes", 20, 1, 100_000),
                r.number("world-modification.protect-radius", 48, 0, 512),
                r.integer("world-modification.protect-built-threshold", 6, 1, 100_000),
                editsEnabled && r.bool("world-modification.door-interaction", true));

        Multiplayer mp = new Multiplayer(
                r.integer("multiplayer.max-participants", 4, 2, 32),
                r.bool("multiplayer.fake-chat", true));

        Memory memory = new Memory(
                r.number("memory.autosave-minutes", 5, 0.5, 1440),
                r.number("memory.forget-after-days", 180, 0, 100_000),
                r.number("memory.heat-half-life-days", 10, 0.1, 3650),
                r.integer("memory.heat-capacity", 96, 8, 2048),
                r.number("memory.personality-relax-per-day", 0.03, 0, 1));

        Performance perf = new Performance(
                r.number("performance.mspt-strained", 40, 5, 1000),
                r.number("performance.mspt-critical", 48, 5, 1000),
                r.number("performance.tps-critical", 17, 1, 20),
                r.number("performance.own-budget-ms", 2.0, 0.1, 50));
        if (perf.msptCritical() <= perf.msptStrained()) {
            r.error("performance.mspt-critical", "必須大於 performance.mspt-strained");
        }

        String fakeJoinName = r.string("messages.fake-join-name", "Herobrine").trim();
        if (!fakeJoinName.matches("[A-Za-z0-9_]{1,16}")) {
            r.error("messages.fake-join-name", "必須是 1-16 個英數字或底線（Minecraft 玩家名稱格式）");
        }
        List<String> signTexts = r.stringList("messages.sign-texts");
        if (signTexts.isEmpty()) {
            r.warn("messages.sign-texts", "沒有告示牌文字，fake_sign 事件將不會發生");
        }
        Map<String, String> landmarkTexts = new LinkedHashMap<>();
        for (String kind : List.of("escape-site", "death-site", "attack-site", "logout-site")) {
            landmarkTexts.put(kind, r.string("messages.landmark-texts." + kind, ""));
        }
        Messages messages = new Messages(fakeJoinName, List.copyOf(signTexts), Map.copyOf(landmarkTexts));

        if (!r.errors().isEmpty()) {
            throw new ConfigException(r.errors(), r.warnings());
        }
        return new HimConfig(enabled, logLevel, worlds, players, director, budget, groupRadius, apparitions, chase,
                edits, mp, memory, perf, messages, impersonation, signs,
                List.copyOf(r.warnings()));
    }

    private static List<String> minionTypes(ConfigReader r) {
        String path = "chase.elite.minions.types";
        if (!r.has(path)) {
            return List.of("ZOMBIE", "SKELETON");
        }
        List<String> out = new ArrayList<>();
        for (String t : r.stringList(path)) {
            String name = t.trim().toUpperCase(Locale.ROOT);
            org.bukkit.entity.EntityType type;
            try {
                type = org.bukkit.entity.EntityType.valueOf(name);
            } catch (IllegalArgumentException e) {
                type = null;
            }
            if (type == null || !type.isAlive() || !type.isSpawnable()
                    || !org.bukkit.entity.Monster.class.isAssignableFrom(type.getEntityClass())) {
                r.error(path, "「" + t + "」不是可生成的敵對生物");
            } else {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static final List<String> POTION_EFFECTS = List.of("SLOWNESS", "WEAKNESS", "DARKNESS");

    private static List<String> potionEffects(ConfigReader r) {
        String path = "chase.weapons.potion-effects";
        if (!r.has(path)) {
            return POTION_EFFECTS;
        }
        List<String> out = new ArrayList<>();
        for (String e : r.stringList(path)) {
            String name = e.trim().toUpperCase(Locale.ROOT);
            if (POTION_EFFECTS.contains(name)) {
                out.add(name);
            } else {
                r.error(path, "未知的效果「" + e + "」，可用：" + String.join(", ", POTION_EFFECTS));
            }
        }
        return List.copyOf(out);
    }
}
