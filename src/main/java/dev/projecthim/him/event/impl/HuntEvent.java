package dev.projecthim.him.event.impl;

import dev.projecthim.him.chase.ChaseBrain;
import dev.projecthim.him.chase.ChaseSettings;
import dev.projecthim.him.chase.HuntGear;
import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.entity.HimCarrier;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.ChaseStats;
import dev.projecthim.him.memory.ChaseStats.EscapeMethod;
import dev.projecthim.him.memory.Landmark;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import dev.projecthim.him.util.MathUtil;
import dev.projecthim.him.util.Vec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * The hunt. A virtual carrier walks the real terrain along planned paths, the visible body follows
 * it, and {@link ChaseBrain} decides what to do every tick. HIM adapts to the player's past: players
 * who escape through water meet a better swimmer, players who outrun him meet a slightly faster one,
 * players who hide get searched for longer, players who keep beating him meet a better armed HIM.
 * Players who make themselves unreachable (pillars, sealed holes) are besieged ({@link SiegeWorks}).
 *
 * <p>Fairness rules: no teleporting while observed, at most a few hidden repositions, configurable
 * damage and lethality, a hard time limit, and several clear ways to escape, including a real,
 * lit shelter, which he never breaks into.
 */
final class HuntEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("hunt", Intent.HUNT)
            .stage(Stage.HUNTED)
            .intensity(0.9)
            .cooldownMinutes(90)
            .weight(1.0)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .visual()
            .requires(c -> !c.inWater() && !c.airborne() && !c.inVehicle() && c.health() > 0.6 && !c.sheltered())
            .fit(c -> (c.night() || c.underground() ? 1.5 : 0.7) * (c.alone() ? 1.2 : 0.8))
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    static final class Instance extends EventInstance {
        private static final int AFTERMATH_MAX = 70;
        private static final double SIGHT_RANGE = 48;
        /** Within this distance he finds a player who is not truly sheltered, seen or not. */
        private static final double SENSE_RANGE = 6;

        private ChaseSettings settings;
        private ChaseBrain brain;
        private HimCarrier carrier;
        private HimBody body;
        private List<Player> viewers;
        private boolean bodyShown;
        /** Seen once from the air: standing on the ground ahead, looking up. */
        private boolean watchingFromBelow;
        private boolean watchedFromBelow;
        private HuntGear.Loadout loadout = HuntGear.Loadout.NONE;
        /** Following a teleported player: when he arrives, and when he gives up looking for a spot. */
        private long arriveAt = -1;
        private long followDeadline;
        private int follows;
        private int hits;
        private long nextAttackAt;
        private Vec lastTargetPos;
        private Vec targetVelocity = Vec.ZERO;
        private final ArrayList<Vec> carrierTrail = new ArrayList<>();
        private boolean himSeesTarget;
        private boolean targetSeesHim;
        private long lastStepSoundAt;
        private final Map<Block, Long> openedDoors = new HashMap<>();
        private ChaseBrain.EndReason endReason;
        private long aftermathSince = -1;
        private int unseenTicks;
        private ChaseBrain.Mode lastMode;
        private double waterFactor = 0.5;
        private SiegeWorks siege;
        private boolean targetSheltered;
        /** Fighting him: his health, and the armour pieces that count against damage. */
        private double health;
        private double maxHealth;
        private int armorCounted;
        private long lastHurtAt = Long.MIN_VALUE / 2;
        private long lastHitByPlayerAt = Long.MIN_VALUE / 2;
        private boolean deathShown;
        /** Bow: drawing until this tick (-1 = not drawing), and when he may draw again. */
        private long drawingUntil = -1;
        private long nextShotAt;
        private long nextPotionAt;
        private int potionsUsed;
        /** Well equipped (diamond and up): he fights like a skilled player. */
        private boolean elite;
        private int strafeSide = 1;
        private long strafeFlipAt;
        private long backoffUntil = -1;
        private long drinkingUntil = -1;
        private long speedBoostUntil = -1;
        private boolean drankSpeed;
        /** A wind-charge leap in progress (-1 = none). */
        private long leapStart = -1;
        private Location leapFrom;
        private Location leapTo;
        private long nextLeapAt;
        private final List<org.bukkit.entity.Mob> minions = new ArrayList<>();
        private boolean minionsSummoned;
        private net.kyori.adventure.bossbar.BossBar bossBar;
        /** Water: trident in hand, charging a throw, a riptide dash, one channeled strike. */
        private boolean tridentOut;
        private long tridentChargeUntil = -1;
        private long nextTridentAt;
        private long riptideStart = -1;
        private Location ripFrom;
        private Location ripTo;
        private long nextRiptideAt;
        private boolean channeled;
        private double nextHitBonus;
        /** The damage bonus of his own weapon, already in the settings (replaced while the trident is out). */
        private double meleeBonus;
        private Pose shownPose = Pose.STANDING;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            PlayerMemory memory = ctx.session().memory();
            settings = adapt(ctx.him().config().chase().settings(), memory.chase());
            boolean revealed = ctx.subvert();
            SpotFinder spots = ctx.him().spots();
            // Nowhere in sight (a pit, a tunnel): he comes from out of view either way.
            Optional<SpotFinder.Spot> spot = (revealed ? spots.distant(p, 12, 20, false) : Optional.<SpotFinder.Spot>empty())
                    .or(() -> spots.hiddenOrBehind(p, revealed ? 12 : 22, revealed ? 24 : 34));
            if (spot.isEmpty()) {
                return false;
            }
            if (!ctx.him().canSpawnApparition()) {
                return false;
            }
            Location at = spot.get().feet();
            carrier = ctx.him().entities().spawnCarrier(at);
            if (carrier == null) {
                return false;
            }
            carrier.setThroughDoors(settings.openDoors());
            body = ctx.him().entities().spawnBody(at);
            if (body == null) {
                return false;
            }
            viewers = new ArrayList<>(ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(p));
            equip(memory, p);
            body.setHittable(true);
            showBody();
            showBossBar();
            siege = new SiegeWorks(ctx.him(), ctx.him().config().chase().siege(), ctx.instanceId(), loadout.tier(),
                    loadout.glint(), () -> body.entity().getEquipment().setItem(EquipmentSlot.HAND,
                    item(heldWeapon(), loadout.glint()), true));
            // Players who keep escaping through water meet a better swimmer.
            waterFactor = memory.chase().favouriteEscape() == EscapeMethod.WATER ? 0.85 : 0.5;
            double aggression = ctx.him().memory().world().personality().get(Trait.AGGRESSION);
            brain = new ChaseBrain(settings, ctx.him().random(), revealed, 0.95 + 0.1 * aggression);
            // Players who keep flying away get less time in the air before it counts.
            int flight = ctx.him().config().chase().flight().escapeTicks();
            brain.setFlightEscapeTicks(memory.chase().favouriteEscape() == EscapeMethod.FLEW ? flight * 3 / 2 : flight);
            if (learns()) {
                memory.chase().recordStart(ctx.him().clock().now());
                ctx.him().memory().world().countHunt();
            }
            lastTargetPos = vec(p.getLocation());
            setWhere(at);
            ctx.him().log().debug(() -> "hunt of " + p.getName() + " begins " + (revealed ? "revealed" : "hidden")
                    + " at " + at.toVector() + " speeds " + settings.walkSpeed() + "/" + settings.runSpeed() + "/"
                    + settings.burstSpeed());
            return true;
        }

        /** Dresses the body for the hunt and lets the gear shape damage, reach and toughness. */
        private void equip(PlayerMemory memory, Player p) {
            var chaseCfg = ctx.him().config().chase();
            var gearCfg = chaseCfg.gear();
            var weapons = chaseCfg.weapons();
            HuntGear.Arsenal arsenal = new HuntGear.Arsenal(weapons.bow(), weapons.potions() && !weapons.potionEffects()
                    .isEmpty(), weapons.spears(), weapons.maces());
            loadout = switch (gearCfg.mode()) {
                case NONE -> HuntGear.Loadout.NONE;
                case FIXED -> fixedLoadout(gearCfg.fixed());
                case ADAPTIVE -> HuntGear.adaptive(memory.chase(),
                        ctx.session().context() != null && ctx.session().context().underground(), ctx.him().random(),
                        gearCfg.maxTier(), gearCfg.enchantGlint(), arsenal);
            };
            dress();
            Material melee = loadout.mainHand();
            double bonus = weaponBonus(melee);
            meleeBonus = bonus;
            armorCounted = gearCfg.armorAffectsRepel() ? Math.min(loadout.armorPieces(), gearCfg.maxRepelBonus()) : 0;
            var combat = chaseCfg.combat();
            maxHealth = combat.health();
            health = maxHealth;
            // Killable: health decides; armour reduces the damage he takes. Otherwise armour adds hits to repel.
            int hitsToRepel = combat.killable() ? Integer.MAX_VALUE / 2 : settings.hitsToRepel() + armorCounted;
            ChaseSettings s = settings;
            settings = new ChaseSettings(s.walkSpeed(), s.runSpeed(), s.burstSpeed(), s.burstTicks(),
                    s.burstCooldownTicks(), s.attackRange() + HuntGear.reachBonus(melee), s.attackDamage() + bonus,
                    HuntGear.isMace(melee) ? (int) (s.attackCooldownTicks() * 1.4) : s.attackCooldownTicks(),
                    s.lethal(), s.maxDurationTicks(), s.revealTicks(), s.loseSightTicks(), s.searchTicks(),
                    s.escapeDistance(), hitsToRepel, s.fakeRetreatChance(), s.maxRepositions(), s.openDoors());
            elite = loadout.tier() >= chaseCfg.elite().minTier();
            HuntGear.Loadout chosen = loadout;
            ctx.him().log().debug(() -> "hunt gear for " + p.getName() + ": " + chosen + (elite ? " (elite)" : ""));
        }

        private double weaponBonus(Material weapon) {
            var gearCfg = ctx.him().config().chase().gear();
            return gearCfg.weaponAffectsDamage() ? Math.min(HuntGear.damageBonus(weapon), gearCfg.maxDamageBonus()) : 0;
        }

        /** What he is holding to fight with right now. */
        private Material heldWeapon() {
            return tridentOut ? Material.TRIDENT : loadout.mainHand();
        }

        /** At the highest tiers the hunted see how much he has left. */
        private void showBossBar() {
            var cfg = ctx.him().config().chase();
            int min = cfg.elite().bossBarMinTier();
            if (min <= 0 || loadout.tier() < min || !cfg.combat().killable()) {
                return;
            }
            bossBar = net.kyori.adventure.bossbar.BossBar.bossBar(Component.text("HIM", NamedTextColor.WHITE), 1f,
                    net.kyori.adventure.bossbar.BossBar.Color.WHITE, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            for (Player v : viewers) {
                v.showBossBar(bossBar);
            }
        }

        private void hideBossBar() {
            if (bossBar == null) {
                return;
            }
            for (Player v : Bukkit.getOnlinePlayers()) {
                v.hideBossBar(bossBar);
            }
            bossBar = null;
        }

        private void dress() {
            var eq = body.entity().getEquipment();
            boolean glint = loadout.glint();
            eq.setItem(EquipmentSlot.HAND, item(loadout.mainHand(), glint), true);
            eq.setItem(EquipmentSlot.OFF_HAND, item(loadout.offHand(), glint), true);
            eq.setItem(EquipmentSlot.HEAD, item(loadout.helmet(), glint), true);
            eq.setItem(EquipmentSlot.CHEST, item(loadout.chestplate(), glint), true);
            eq.setItem(EquipmentSlot.LEGS, item(loadout.leggings(), glint), true);
            eq.setItem(EquipmentSlot.FEET, item(loadout.boots(), glint), true);
        }

        private HuntGear.Loadout fixedLoadout(java.util.Map<String, String> fixed) {
            var gearCfg = ctx.him().config().chase().gear();
            return HuntGear.Loadout.fixed(material(fixed.get("main-hand")), material(fixed.get("off-hand")),
                    material(fixed.get("helmet")), material(fixed.get("chestplate")), material(fixed.get("leggings")),
                    material(fixed.get("boots")), material(fixed.get("ranged")), gearCfg.fixedPotions(),
                    gearCfg.fixedGlint());
        }

        private static org.bukkit.Material material(String name) {
            return name == null ? null : org.bukkit.Material.matchMaterial(name);
        }

        private static ItemStack item(org.bukkit.Material m, boolean glint) {
            if (m == null || !m.isItem()) {
                return null;
            }
            ItemStack stack = new ItemStack(m);
            if (glint) {
                // Only the shine: no real enchantments on items nobody can ever pick up anyway.
                stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            }
            return stack;
        }

        /** Manual (administrator) hunts do not change the player's hunt statistics. */
        private boolean learns() {
            return ctx.trigger() != dev.projecthim.him.director.Trigger.MANUAL;
        }

        /** Counters the player's habit, within the configured caps. */
        static ChaseSettings adapt(ChaseSettings s, ChaseStats stats) {
            EscapeMethod habit = stats.favouriteEscape();
            if (habit == null) {
                return s;
            }
            return switch (habit) {
                case OUTRAN -> new ChaseSettings(s.walkSpeed(), Math.min(s.runSpeed() * 1.05, 5.5), s.burstSpeed(),
                        s.burstTicks() + 6, s.burstCooldownTicks(), s.attackRange(), s.attackDamage(),
                        s.attackCooldownTicks(), s.lethal(), s.maxDurationTicks(), s.revealTicks(), s.loseSightTicks(),
                        s.searchTicks(), s.escapeDistance() + 8, s.hitsToRepel(), s.fakeRetreatChance(),
                        s.maxRepositions(), s.openDoors());
                case BROKE_SIGHT, SHELTER -> new ChaseSettings(s.walkSpeed(), s.runSpeed(), s.burstSpeed(),
                        s.burstTicks(), s.burstCooldownTicks(), s.attackRange(), s.attackDamage(),
                        s.attackCooldownTicks(), s.lethal(), s.maxDurationTicks(), s.revealTicks(),
                        (int) (s.loseSightTicks() * 1.4), (int) (s.searchTicks() * 1.5), s.escapeDistance(),
                        s.hitsToRepel(), s.fakeRetreatChance(), s.maxRepositions(), s.openDoors());
                case FOUGHT -> new ChaseSettings(s.walkSpeed(), s.runSpeed(), s.burstSpeed(), s.burstTicks(),
                        s.burstCooldownTicks(), s.attackRange(), s.attackDamage(), s.attackCooldownTicks(), s.lethal(),
                        s.maxDurationTicks(), s.revealTicks(), s.loseSightTicks(), s.searchTicks(), s.escapeDistance(),
                        s.hitsToRepel() + 2, Math.min(1, s.fakeRetreatChance() * 1.5), s.maxRepositions(), s.openDoors());
                default -> s;
            };
        }

        private static Vec vec(Location l) {
            return new Vec(l.getX(), l.getY(), l.getZ());
        }

        private void showBody() {
            for (Player v : viewers) {
                if (v.isOnline()) {
                    body.show(v);
                }
            }
            bodyShown = true;
        }

        private void hideBody() {
            body.hideAll();
            bodyShown = false;
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            if (aftermathSince >= 0) {
                aftermath(tick);
                return;
            }
            if (arriveAt >= 0) {
                if (tick >= arriveAt) {
                    arrive(tick, p);
                }
                return;
            }
            if (!carrier.valid() || !body.valid()) {
                end(ChaseBrain.EndReason.ABORTED);
                return;
            }
            Location carrierLoc = carrier.location();
            if (p.getWorld() != carrierLoc.getWorld()) {
                end(ChaseBrain.EndReason.TARGET_GONE);
                return;
            }
            Location pl = p.getLocation();
            Vec targetPos = vec(pl);
            Vec rawVelocity = targetPos.subtract(lastTargetPos);
            targetVelocity = new Vec(MathUtil.ema(targetVelocity.x(), rawVelocity.x(), 0.3),
                    MathUtil.ema(targetVelocity.y(), rawVelocity.y(), 0.3),
                    MathUtil.ema(targetVelocity.z(), rawVelocity.z(), 0.3));
            lastTargetPos = targetPos;

            targetSheltered = p.getLocation().getBlock().getLightFromSky() <= 3
                    && p.getWorld().getHighestBlockYAt(pl) > pl.getBlockY() + 1
                    && p.getLocation().getBlock().getLightFromBlocks() >= 8;
            if ((tick & 1) == 0) {
                Location himEye = carrierLoc.clone().add(0, 1.62, 0);
                // He perceives the player within a limited range, like anyone would.
                himSeesTarget = himEye.distanceSquared(p.getEyeLocation()) <= SIGHT_RANGE * SIGHT_RANGE
                        && Sight.visibility(carrierLoc.getWorld(), himEye.toVector(), p.getEyeLocation().toVector()) >= 0.4
                        // Up close he hears them: a hole in the ground or a wall of dirt is no hiding place.
                        || !targetSheltered && carrierLoc.distanceSquared(pl) <= SENSE_RANGE * SENSE_RANGE;
                targetSeesHim = Sight.canSee(p, carrierLoc, Sight.PERIPHERAL_HALF_ANGLE);
            }
            carrierTrail.add(vec(carrierLoc));
            if (carrierTrail.size() > 40) {
                carrierTrail.removeFirst();
            }
            boolean progressing = carrierTrail.size() < 40
                    || carrierTrail.getFirst().distance(carrierTrail.getLast()) > 1.5;
            // Close counts as reachable only while he still gets closer: a fence or a single block
            // between them must not leave him standing there forever.
            boolean reachable = carrier.lastPlanReachedGoal()
                    || carrierLoc.distance(pl) <= settings.attackRange() + (progressing ? 1.0 : 0.0);

            ChaseBrain.Command cmd = brain.tick(new ChaseBrain.Inputs(tick, vec(carrierLoc), targetPos, targetVelocity,
                    himSeesTarget, targetSeesHim, p.isInWater(), targetSheltered, progressing, reachable, hits,
                    p.isDead(), !p.isOnline(), flying(p)));
            if (cmd.end() != null) {
                end(cmd.end());
                return;
            }
            execute(cmd, tick, p, carrierLoc);
            if (tick % 100 == 0) {
                Location c = carrierLoc;
                ctx.him().log().debug(() -> String.format(Locale.ROOT,
                        "hunt of %s: %s at %.1f,%.1f,%.1f dist %.1f reachable=%b sheltered=%b sees=%b shown=%b attack=%b siege=%d",
                        p.getName(), cmd.mode(), c.getX(), c.getY(), c.getZ(), c.distance(pl), reachable,
                        targetSheltered, himSeesTarget, bodyShown, cmd.attackAllowed(), siege.used()));
            }
        }

        /**
         * Arrives where the teleported player went: out of their view, a few seconds after them, in
         * another world if need be (a new body there; entities do not travel between worlds cleanly).
         */
        private void arrive(long tick, Player p) {
            if (!ctx.him().sessions().eligible(p)) {
                end(ChaseBrain.EndReason.TELEPORTED);
                return;
            }
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(p, 12, 24);
            if (spot.isEmpty() || Sight.canSee(p, spot.get().feet(), Sight.PERIPHERAL_HALF_ANGLE)) {
                if (tick >= followDeadline) {
                    end(ChaseBrain.EndReason.TELEPORTED);
                }
                return;
            }
            Location at = spot.get().feet();
            hideBody();
            if (body.entity().getWorld() != at.getWorld()) {
                ctx.him().entities().remove(body);
                body = ctx.him().entities().spawnBody(at);
                if (body == null) {
                    end(ChaseBrain.EndReason.ABORTED);
                    return;
                }
                dress();
                body.setHittable(true);
            } else {
                body.moveTo(at);
            }
            carrier.teleport(at);
            carrierTrail.clear();
            lastTargetPos = vec(p.getLocation());
            targetVelocity = Vec.ZERO;
            arriveAt = -1;
            brain.onFollowed(new ChaseBrain.Inputs(tick, vec(at), vec(p.getLocation()), Vec.ZERO, true, false,
                    false, false, true, false, 0, false, false, false));
            ctx.him().log().debug(() -> "hunt: followed " + p.getName() + " (" + follows + ") to " + at.toVector()
                    + " in " + at.getWorld().getName());
        }

        private static boolean flying(Player p) {
            return p.isGliding() || p.isFlying() && p.getGameMode() == org.bukkit.GameMode.SURVIVAL;
        }

        private void execute(ChaseBrain.Command cmd, long tick, Player p, Location carrierLoc) {
            if (cmd.mode() == ChaseBrain.Mode.LANDING) {
                land(p, carrierLoc);
                carrierLoc = carrier.location();
            } else if (cmd.mode() == ChaseBrain.Mode.AIRBORNE) {
                watchFromBelow(tick, p, carrierLoc);
                carrierLoc = carrier.location();
            } else {
                watchingFromBelow = false;
            }
            if (cmd.reposition() && !targetSeesHim) {
                reposition(p);
                carrierLoc = carrier.location();
            }
            updateWaterMode(p, carrierLoc);
            if (leapStart >= 0) {
                leapTick(tick, p);
            } else if (riptideStart >= 0) {
                riptideTick(tick, p);
            } else if (drawingUntil >= 0 || tridentChargeUntil >= 0) {
                // Drawing the bow or raising the trident: he stands still and aims.
                carrier.stop();
            } else if (cmd.speed() > 0 && cmd.destination() != null) {
                boolean swimming = carrierLoc.getBlock().getType() == org.bukkit.Material.WATER;
                double boost = tick < speedBoostUntil ? ctx.him().config().chase().elite().speedBoost() : 1.0;
                double drinking = drinkingUntil >= 0 ? 0.5 : 1.0;
                double swim = tridentOut ? ctx.him().config().chase().water().swimFactor() : waterFactor;
                carrier.setSpeed(cmd.speed() * (swimming ? swim : 1.0) * boost * drinking);
                Location dest = tactics(tick, p, carrierLoc, cmd, new Location(carrierLoc.getWorld(),
                        cmd.destination().x(), cmd.destination().y(), cmd.destination().z()));
                carrier.navigate(dest, tick, cmd.mode() == ChaseBrain.Mode.SEARCH ? 20 : 8);
                carrier.step();
                if (cmd.breach() && !targetSheltered && siege.tick(tick, carrier, body, p, viewers)) {
                    // The terrain changed: plan again from scratch.
                    carrier.stop();
                }
                if (settings.openDoors()) {
                    openDoorsAhead(carrierLoc, tick);
                }
            } else {
                carrier.stop();
            }
            closeDoorsBehind(tick);
            siege.idle(tick);

            boolean visible = cmd.bodyVisible() || watchingFromBelow;
            if (visible && !bodyShown) {
                // Reappear only where nobody is looking (far below a flying player: like anything loading in).
                if (!targetSeesHim || watchingFromBelow) {
                    showBody();
                }
            } else if (!visible && bodyShown) {
                hideBody();
            }

            Location follow = carrier.location();
            Location eye = p.getEyeLocation();
            if (cmd.faceTarget()) {
                follow.setYaw(MathUtil.yawTowards(follow.getX(), follow.getZ(), eye.getX(), eye.getZ()));
                follow.setPitch(MathUtil.pitchTowards(follow.getX(), follow.getY() + 1.62, follow.getZ(), eye.getX(),
                        eye.getY(), eye.getZ()));
            }
            body.moveTo(follow);
            if (cmd.faceTarget()) {
                body.entity().setBodyYaw(follow.getYaw());
            }

            double distance = follow.distance(p.getLocation());
            if (bodyShown && cmd.speed() > 3.5 && tick - lastStepSoundAt >= 6 && distance < 28) {
                lastStepSoundAt = tick;
                for (Player v : viewers) {
                    if (v.isOnline()) {
                        SoundCues.footstep(v, follow, 0.7f);
                    }
                }
            }
            if (cmd.mode() == ChaseBrain.Mode.REVEAL && lastMode != ChaseBrain.Mode.REVEAL) {
                SoundCues.play(viewers, follow, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.8f, 0.6f);
            }
            lastMode = cmd.mode();
            double horizontal = Math.hypot(follow.getX() - p.getX(), follow.getZ() - p.getZ());
            double dy = follow.getY() - p.getY();
            boolean maceSmash = HuntGear.smashBonus(loadout.mainHand(), dy) > 0 && horizontal <= settings.attackRange();
            if (cmd.attackAllowed() && tick >= nextAttackAt && bodyShown && himSeesTarget && drawingUntil < 0
                    && leapStart < 0 && riptideStart < 0 && tridentChargeUntil < 0) {
                if (horizontal <= settings.attackRange() && (Math.abs(dy) < 2.0 || maceSmash)) {
                    attack(p, follow, cmd.mode(), dy);
                    nextAttackAt = tick + settings.attackCooldownTicks();
                }
            }
            ranged(tick, p, follow, cmd, distance);
            elitePlays(tick, p, follow, cmd, distance);
            waterPlays(tick, p, follow, cmd, distance);
            updatePose(carrier.location());
        }

        /** Rain falls on this spot (a storm, open sky above, water does not count as a roof). */
        private static boolean rainedOn(Location l) {
            World w = l.getWorld();
            // OCEAN_FLOOR ignores water: a swimmer under the open sky is out in the rain.
            return w.hasStorm() && w.getHighestBlockYAt(l, org.bukkit.HeightMap.OCEAN_FLOOR) < l.getBlockY();
        }

        private static boolean wet(Location l) {
            return l.getBlock().getType() == Material.WATER || rainedOn(l);
        }

        /**
         * The player took to the water, a boat or the rain: out comes the trident. Back on dry land,
         * he takes up his own weapon again.
         */
        private void updateWaterMode(Player p, Location me) {
            var water = ctx.him().config().chase().water();
            boolean want = water.enabled() && loadout.tier() >= water.minTier() && aftermathSince < 0
                    && (p.isInWater() || p.getVehicle() instanceof org.bukkit.entity.Boat || rainedOn(p.getLocation())
                    && rainedOn(me));
            if (want == tridentOut || drawingUntil >= 0 || drinkingUntil >= 0) {
                return;
            }
            tridentOut = want;
            siege.handTaken();
            body.entity().getEquipment().setItem(EquipmentSlot.HAND,
                    item(want ? Material.TRIDENT : loadout.mainHand(), loadout.glint()), true);
            ctx.him().log().debug(() -> "hunt: " + (want ? "trident out" : "trident away"));
        }

        /** Swimming in water, spinning in a riptide, standing otherwise. */
        private void updatePose(Location me) {
            if (deathShown) {
                return;
            }
            Pose want = riptideStart >= 0 ? Pose.SPIN_ATTACK
                    : me.getBlock().getType() == Material.WATER
                    && me.clone().add(0, 1, 0).getBlock().getType() == Material.WATER ? Pose.SWIMMING : Pose.STANDING;
            if (want != shownPose && Mannequin.validPoses().contains(want)) {
                shownPose = want;
                body.entity().setPose(want, true);
            }
        }

        /** Thrown tridents, riptide dashes and, in a thunderstorm, lightning. */
        private void waterPlays(long tick, Player p, Location from, ChaseBrain.Command cmd, double distance) {
            if (!tridentOut || aftermathSince >= 0 || riptideStart >= 0) {
                return;
            }
            var water = ctx.him().config().chase().water();
            if (tridentChargeUntil >= 0) {
                if (tick >= tridentChargeUntil) {
                    throwTrident(tick, p);
                }
                return;
            }
            boolean pursuing = cmd.mode() == ChaseBrain.Mode.PURSUE || cmd.mode() == ChaseBrain.Mode.BURST
                    || cmd.mode() == ChaseBrain.Mode.SIEGE;
            if (!pursuing || !bodyShown || !himSeesTarget || drawingUntil >= 0 || leapStart >= 0) {
                return;
            }
            if (water.channeling() && !channeled && loadout.tier() >= 4 && p.getWorld().isThundering()
                    && rainedOn(p.getLocation()) && distance <= 24) {
                channel(p);
                return;
            }
            if (water.riptide() && elite && tick >= nextRiptideAt && wet(from) && distance >= 6 && distance <= 20
                    && startRiptide(tick, p, from)) {
                return;
            }
            if (tick >= nextTridentAt && distance >= 5 && distance <= 22) {
                body.entity().startUsingItem(EquipmentSlot.HAND);
                tridentChargeUntil = tick + 10;
            }
        }

        private void throwTrident(long tick, Player p) {
            tridentChargeUntil = -1;
            nextTridentAt = tick + ctx.him().config().chase().water().tridentCooldownTicks();
            Mannequin m = body.entity();
            Location eye = m.getEyeLocation();
            double speed = 2.5;
            double d = eye.distance(p.getEyeLocation());
            double lead = d / speed;
            Vector aim = p.getLocation().toVector().add(new Vector(targetVelocity.x() * lead, 0.9, targetVelocity.z() * lead))
                    .subtract(eye.toVector());
            aim.setY(aim.getY() + HuntGear.arrowLift(d, speed));
            m.clearActiveItem();
            if (aim.lengthSquared() < 1.0E-4 || !himSeesTarget) {
                return;
            }
            org.bukkit.entity.Trident trident = m.launchProjectile(org.bukkit.entity.Trident.class,
                    aim.normalize().multiply(speed));
            trident.setPersistent(false);
            trident.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            trident.setVisibleByDefault(false);
            ctx.him().entities().tags().markProjectile(trident, p.getUniqueId());
            for (Player v : viewers) {
                if (v.isOnline()) {
                    v.showEntity(ctx.him().plugin(), trident);
                }
            }
            m.swingMainHand();
            SoundCues.play(viewers, eye, Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 1.0f, 0.9f);
            ctx.him().log().debug(() -> "hunt: threw a trident at " + p.getName());
        }

        private static final int RIPTIDE_TICKS = 10;

        /** Riptide: a straight dash through water or rain, ending at the player. Only along a clear line. */
        private boolean startRiptide(long tick, Player p, Location from) {
            nextRiptideAt = tick + 40;
            Location pl = p.getLocation();
            Vector line = pl.toVector().subtract(from.toVector());
            double length = line.length();
            if (length < 3) {
                return false;
            }
            Location to = from.clone().add(line.clone().multiply((length - 1.2) / length));
            for (double t = 0.1; t <= 1.0; t += 0.1) {
                Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t));
                if (!at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) {
                    return false;
                }
            }
            ripFrom = from.clone();
            ripTo = to;
            riptideStart = tick;
            nextRiptideAt = tick + 120;
            SoundCues.play(viewers, from, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.HOSTILE, 1.0f, 1.0f);
            ctx.him().log().debug(() -> "hunt: riptide towards " + p.getName());
            return true;
        }

        private void riptideTick(long tick, Player p) {
            double t = Math.min(1, (tick - riptideStart) / (double) RIPTIDE_TICKS);
            Location at = ripFrom.clone().add(ripTo.clone().subtract(ripFrom).toVector().multiply(t));
            at.setYaw(MathUtil.yawTowards(at.getX(), at.getZ(), p.getX(), p.getZ()));
            carrier.teleport(at);
            for (Player v : viewers) {
                if (v.isOnline() && v.getWorld() == at.getWorld()) {
                    v.spawnParticle(Particle.BUBBLE, at.clone().add(0, 0.8, 0), 6, 0.3, 0.3, 0.3, 0.05);
                }
            }
            if (t < 1) {
                return;
            }
            riptideStart = -1;
            if (at.distance(p.getLocation()) <= settings.attackRange() + 1.0) {
                nextHitBonus = 2;
                attack(p, at, ChaseBrain.Mode.BURST, 0);
                nextAttackAt = tick + settings.attackCooldownTicks();
            }
        }

        /** Netherite, a thunderstorm, the trident raised: lightning finds the player. Once per hunt. */
        private void channel(Player p) {
            channeled = true;
            var water = ctx.him().config().chase().water();
            body.entity().swingMainHand();
            SoundCues.play(viewers, p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, SoundCategory.WEATHER, 1.0f, 1.0f);
            p.getWorld().strikeLightningEffect(p.getLocation());
            double damage = water.channelingDamage();
            if (!settings.lethal()) {
                damage = Math.min(damage, Math.max(0, p.getHealth() - 1.0));
            }
            if (damage > 0) {
                p.damage(damage, DamageSource.builder(DamageType.LIGHTNING_BOLT)
                        .withCausingEntity(body.entity())
                        .withDirectEntity(body.entity())
                        .withDamageLocation(p.getLocation())
                        .build());
            }
            ctx.him().log().debug(() -> "hunt: channeled lightning onto " + p.getName());
        }

        /** The boat breaks (and drops, as boats do); the player is in the water. */
        private void sinkBoat(org.bukkit.entity.Boat boat) {
            Location at = boat.getLocation();
            Material item = boat.getBoatMaterial();
            boat.eject();
            boat.remove();
            if (item != null && item.isItem()) {
                at.getWorld().dropItemNaturally(at, new ItemStack(item));
            }
            SoundCues.play(viewers, at, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.HOSTILE, 1.0f, 0.6f);
            ctx.him().log().debug(() -> "hunt: broke the boat");
        }

        /**
         * Footwork of a skilled fighter (elite only): strafing up close, backing off after a hit,
         * keeping a spear's distance, sidestepping a drawn bow.
         */
        private Location tactics(long tick, Player p, Location me, ChaseBrain.Command cmd, Location dest) {
            boolean closing = cmd.mode() == ChaseBrain.Mode.PURSUE || cmd.mode() == ChaseBrain.Mode.BURST;
            // Only on level ground with them: footwork towards a side point would hide that the player
            // is out of reach (a pillar), and the siege would never start.
            if (!elite || !closing || me.getWorld() != p.getWorld() || Math.abs(p.getY() - me.getY()) >= 1.5) {
                return dest;
            }
            Location pl = p.getLocation();
            Vector toMe = me.toVector().subtract(pl.toVector()).setY(0);
            double distance = toMe.length();
            if (distance < 1.0E-3) {
                return dest;
            }
            Vector away = toMe.clone().normalize();
            if (tick < backoffUntil) {
                return me.clone().add(away.multiply(3));
            }
            if (tick >= strafeFlipAt) {
                strafeSide = ctx.him().random().nextBoolean() ? 1 : -1;
                strafeFlipAt = tick + 15 + ctx.him().random().nextInt(25);
            }
            Vector side = new Vector(-away.getZ(), 0, away.getX()).multiply(strafeSide);
            Material held = p.getInventory().getItemInMainHand().getType();
            boolean aimingAtHim = p.getActiveItem().getType() == Material.BOW || p.getActiveItem().getType() == Material.CROSSBOW
                    || held == Material.CROSSBOW;
            if (aimingAtHim && distance > 4) {
                // Never run straight at a drawn bow.
                return dest.clone().add(side.multiply(3));
            }
            if (HuntGear.isSpear(heldWeapon()) && distance < settings.attackRange() + 1.5) {
                // Keeps them at the end of the spear.
                return pl.clone().add(away.multiply(settings.attackRange() - 0.5)).add(side.multiply(0.8));
            }
            if (distance < 6) {
                return dest.clone().add(side.multiply(1.6));
            }
            return dest;
        }

        /** Elite only: a wind-charge leap with the mace, a speed potion against runners. */
        private void elitePlays(long tick, Player p, Location from, ChaseBrain.Command cmd, double distance) {
            if (!elite || aftermathSince >= 0) {
                return;
            }
            var cfg = ctx.him().config().chase().elite();
            if (drinkingUntil >= 0) {
                if (tick >= drinkingUntil) {
                    drinkingUntil = -1;
                    speedBoostUntil = tick + 400;
                    body.entity().clearActiveItem();
                    body.entity().getEquipment().setItem(EquipmentSlot.OFF_HAND, null, true);
                }
                return;
            }
            boolean pursuing = cmd.mode() == ChaseBrain.Mode.PURSUE || cmd.mode() == ChaseBrain.Mode.BURST;
            if (!pursuing || !bodyShown || drawingUntil >= 0 || leapStart >= 0) {
                return;
            }
            boolean fleeing = new Vec(targetVelocity.x(), 0, targetVelocity.z()).length() > 0.15
                    && p.getLocation().toVector().subtract(from.toVector()).dot(
                            new Vector(targetVelocity.x(), 0, targetVelocity.z())) > 0;
            if (cfg.speedPotion() && !drankSpeed && fleeing && distance > 10) {
                drankSpeed = true;
                ItemStack potion = new ItemStack(Material.POTION);
                potion.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(org.bukkit.potion.PotionType.SWIFTNESS));
                body.entity().getEquipment().setItem(EquipmentSlot.OFF_HAND, potion, true);
                body.entity().startUsingItem(EquipmentSlot.OFF_HAND);
                drinkingUntil = tick + 24;
                SoundCues.play(viewers, from, Sound.ENTITY_GENERIC_DRINK, SoundCategory.HOSTILE, 1.0f, 0.8f);
                ctx.him().log().debug(() -> "hunt: drinks a speed potion");
                return;
            }
            if (cfg.leap() && HuntGear.isMace(loadout.mainHand()) && tick >= nextLeapAt && himSeesTarget
                    && distance >= 4 && distance <= 12) {
                startLeap(tick, p, from);
            }
        }

        private static final double LEAP_APEX = 5;
        private static final int LEAP_TICKS = 18;

        /** A wind charge under his feet: up, over, and down on them. Only along a clear arc. */
        private void startLeap(long tick, Player p, Location from) {
            nextLeapAt = tick + 60;
            Location pl = p.getLocation();
            Location aim = pl.clone().add(targetVelocity.x() * LEAP_TICKS, 0, targetVelocity.z() * LEAP_TICKS);
            Optional<Location> landing = Ground.findStandable(p.getWorld(), aim.getBlockX(), aim.getBlockY(),
                    aim.getBlockZ(), 2, 3);
            if (landing.isEmpty()) {
                return;
            }
            Location to = landing.get();
            for (int i = 1; i < 10; i++) {
                double t = i / 10.0;
                Location at = arc(from, to, t);
                for (int dy = 0; dy <= 1; dy++) {
                    if (!Ground.free(at.getWorld().getType(at.getBlockX(), at.getBlockY() + dy, at.getBlockZ()))) {
                        return;
                    }
                }
            }
            leapFrom = from.clone();
            leapTo = to;
            leapStart = tick;
            nextLeapAt = tick + 160;
            SoundCues.play(viewers, from, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.HOSTILE, 1.0f, 1.0f);
            for (Player v : viewers) {
                if (v.isOnline() && v.getWorld() == from.getWorld()) {
                    v.spawnParticle(Particle.GUST_EMITTER_SMALL, from, 1);
                }
            }
            ctx.him().log().debug(() -> "hunt: wind-charge leap towards " + p.getName());
        }

        private static Location arc(Location from, Location to, double t) {
            Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t));
            at.add(0, HuntGear.leapHeight(t, LEAP_APEX), 0);
            return at;
        }

        private void leapTick(long tick, Player p) {
            double t = (tick - leapStart) / (double) LEAP_TICKS;
            Location at = arc(leapFrom, leapTo, Math.min(1, t));
            at.setYaw(MathUtil.yawTowards(at.getX(), at.getZ(), p.getX(), p.getZ()));
            carrier.teleport(at);
            if (t < 1) {
                return;
            }
            leapStart = -1;
            Location pl = p.getLocation();
            double horizontal = Math.hypot(leapTo.getX() - pl.getX(), leapTo.getZ() - pl.getZ());
            if (horizontal <= settings.attackRange() + 0.5 && Math.abs(leapTo.getY() - pl.getY()) < 2.5) {
                attack(p, leapTo, ChaseBrain.Mode.BURST, LEAP_APEX);
                nextAttackAt = tick + settings.attackCooldownTicks();
            }
        }

        /** At the highest tier, badly hurt: he calls others to fight with him. */
        private void summonMinions(Player p) {
            var cfg = ctx.him().config().chase().elite();
            minionsSummoned = true;
            SoundCues.play(viewers, body.location(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 1.0f, 0.6f);
            for (int i = 0; i < cfg.minionCount() && !cfg.minionTypes().isEmpty(); i++) {
                Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(p, 4, 10);
                Location at = spot.map(SpotFinder.Spot::feet).orElseGet(() ->
                        ctx.him().spots().near(p.getLocation(), 6).orElse(null));
                if (at == null) {
                    continue;
                }
                org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(
                        cfg.minionTypes().get(ctx.him().random().nextInt(cfg.minionTypes().size())));
                org.bukkit.entity.Entity e = at.getWorld().spawnEntity(at, type,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
                            spawned.setPersistent(false);
                            ctx.him().entities().tags().markMinion(spawned, p.getUniqueId());
                        });
                if (e instanceof org.bukkit.entity.Mob mob && mob.isValid()) {
                    mob.setTarget(p);
                    mob.setRemoveWhenFarAway(true);
                    minions.add(mob);
                    for (Player v : viewers) {
                        if (v.isOnline() && v.getWorld() == at.getWorld()) {
                            v.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.8, 0), 20, 0.3, 0.6, 0.3, 0.02);
                        }
                    }
                }
            }
            ctx.him().log().debug(() -> "hunt: summoned " + minions.size() + " to fight with him");
        }

        private void dismissMinions() {
            for (org.bukkit.entity.Mob m : minions) {
                if (m.isValid()) {
                    m.getWorld().spawnParticle(Particle.LARGE_SMOKE, m.getLocation().add(0, 0.8, 0), 20, 0.3, 0.6, 0.3,
                            0.02);
                    m.remove();
                }
            }
            minions.clear();
        }

        /**
         * Bow and splash potions, for the players who meet a HIM carrying them. Only while he is
         * visible and can see them; never more than the configured pace.
         */
        private void ranged(long tick, Player p, Location from, ChaseBrain.Command cmd, double distance) {
            if (drawingUntil >= 0) {
                if (tick >= drawingUntil) {
                    releaseArrow(tick, p);
                }
                return;
            }
            boolean pursuing = cmd.mode() == ChaseBrain.Mode.PURSUE || cmd.mode() == ChaseBrain.Mode.SIEGE;
            if (!bodyShown || !himSeesTarget || !pursuing || aftermathSince >= 0) {
                return;
            }
            boolean fleeing = new Vec(targetVelocity.x(), 0, targetVelocity.z()).length() > 0.15
                    && p.getLocation().toVector().subtract(from.toVector()).dot(
                            new Vector(targetVelocity.x(), 0, targetVelocity.z())) > 0;
            var weapons = ctx.him().config().chase().weapons();
            if (loadout.ranged() != null && !tridentOut && tick >= nextShotAt && distance >= 7 && distance <= 26
                    && (fleeing || cmd.mode() == ChaseBrain.Mode.SIEGE || ctx.him().random().nextDouble() < 0.01)) {
                // Out comes the bow.
                siege.handTaken();
                body.entity().getEquipment().setItem(EquipmentSlot.HAND, item(loadout.ranged(), loadout.glint()), true);
                body.entity().startUsingItem(EquipmentSlot.HAND);
                drawingUntil = tick + 20;
                return;
            }
            if (loadout.potions() && potionsUsed < weapons.maxPotions() && tick >= nextPotionAt && distance >= 3.5
                    && distance <= 12) {
                PotionEffectType effect = choosePotion(tick, p, fleeing, weapons.potionEffects());
                if (effect != null) {
                    throwPotion(p, from, effect, distance);
                    nextPotionAt = tick + weapons.potionCooldownTicks();
                    potionsUsed++;
                }
            }
        }

        private void releaseArrow(long tick, Player p) {
            drawingUntil = -1;
            nextShotAt = tick + ctx.him().config().chase().weapons().bowCooldownTicks();
            Mannequin m = body.entity();
            Location eye = m.getEyeLocation();
            double speed = 2.6;
            double d = eye.distance(p.getEyeLocation());
            double lead = d / speed;
            Vector aim = p.getLocation().toVector().add(new Vector(targetVelocity.x() * lead, 1.1, targetVelocity.z() * lead))
                    .subtract(eye.toVector());
            aim.setY(aim.getY() + HuntGear.arrowLift(d, speed));
            if (aim.lengthSquared() > 1.0E-4 && himSeesTarget) {
                Arrow arrow = m.launchProjectile(Arrow.class, aim.normalize().multiply(speed));
                arrow.setPersistent(false);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                arrow.setVisibleByDefault(false);
                ctx.him().entities().tags().markProjectile(arrow, p.getUniqueId());
                for (Player v : viewers) {
                    if (v.isOnline()) {
                        v.showEntity(ctx.him().plugin(), arrow);
                    }
                }
                SoundCues.play(viewers, eye, Sound.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.0f, 0.8f);
            }
            m.clearActiveItem();
            m.getEquipment().setItem(EquipmentSlot.HAND, item(heldWeapon(), loadout.glint()), true);
        }

        /** Slowness for runners, weakness for fighters, darkness in the dark. */
        private PotionEffectType choosePotion(long tick, Player p, boolean fleeing, List<String> allowed) {
            if (fleeing && p.isSprinting() && allowed.contains("SLOWNESS") && !p.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                return PotionEffectType.SLOWNESS;
            }
            if (tick - lastHitByPlayerAt < 100 && allowed.contains("WEAKNESS")
                    && !p.hasPotionEffect(PotionEffectType.WEAKNESS)) {
                return PotionEffectType.WEAKNESS;
            }
            if (p.getLocation().getBlock().getLightLevel() <= 7 && allowed.contains("DARKNESS")
                    && !p.hasPotionEffect(PotionEffectType.DARKNESS) && ctx.him().random().nextDouble() < 0.02) {
                return PotionEffectType.DARKNESS;
            }
            return null;
        }

        private void throwPotion(Player p, Location from, PotionEffectType effect, double distance) {
            ItemStack stack = new ItemStack(Material.SPLASH_POTION);
            int duration = effect == PotionEffectType.DARKNESS ? 120 : effect == PotionEffectType.WEAKNESS ? 100 : 80;
            stack.editMeta(PotionMeta.class, meta -> meta.addCustomEffect(new PotionEffect(effect, duration, 0), true));
            Mannequin m = body.entity();
            Vector to = p.getLocation().toVector().subtract(m.getEyeLocation().toVector());
            Vector vel = to.clone().setY(0).normalize().multiply(0.35 + distance * 0.045).setY(0.25 + to.getY() * 0.05);
            ThrownPotion potion = m.launchProjectile(ThrownPotion.class, vel);
            potion.setItem(stack);
            potion.setPersistent(false);
            potion.setVisibleByDefault(false);
            ctx.him().entities().tags().markProjectile(potion, p.getUniqueId());
            for (Player v : viewers) {
                if (v.isOnline()) {
                    v.showEntity(ctx.him().plugin(), potion);
                }
            }
            m.swingOffHand();
            SoundCues.play(viewers, from, Sound.ENTITY_WITCH_THROW, SoundCategory.HOSTILE, 1.0f, 0.7f);
            ctx.him().log().debug(() -> "hunt: threw " + effect.getKey().getKey() + " at " + p.getName());
        }

        private void attack(Player p, Location from, ChaseBrain.Mode mode, double heightAbove) {
            body.entity().swingMainHand();
            Material weapon = heldWeapon();
            double damage = settings.attackDamage() - meleeBonus + weaponBonus(weapon);
            double knock = 0.45;
            if (HuntGear.isAxe(weapon) && p.isBlocking()) {
                // An axe takes the shield out of their hands for a while, as it would for anyone.
                p.setCooldown(Material.SHIELD, 100);
                p.clearActiveItem();
                SoundCues.play(viewers, p.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1.0f, 0.8f);
            }
            if (HuntGear.isSpear(weapon) && mode == ChaseBrain.Mode.BURST) {
                // A lunge.
                damage += 1.5;
                knock = 0.8;
            }
            damage += nextHitBonus;
            nextHitBonus = 0;
            var water = ctx.him().config().chase().water();
            if (water.sinkBoats() && p.getVehicle() instanceof org.bukkit.entity.Boat boat) {
                sinkBoat(boat);
            }
            double smash = HuntGear.smashBonus(weapon, heightAbove);
            if (smash > 0) {
                damage += smash;
                knock = 0.9;
                SoundCues.play(viewers, p.getLocation(), Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.0f,
                        0.8f);
                for (Player v : viewers) {
                    if (v.isOnline() && v.getWorld() == p.getWorld()) {
                        v.spawnParticle(Particle.BLOCK, p.getLocation(), 30, 0.6, 0.1, 0.6,
                                p.getLocation().subtract(0, 0.5, 0).getBlock().getBlockData());
                    }
                }
            }
            if (!settings.lethal()) {
                damage = Math.min(damage, Math.max(0, p.getHealth() - 1.0));
            }
            if (damage > 0) {
                p.damage(damage, DamageSource.builder(DamageType.MOB_ATTACK)
                        .withCausingEntity(body.entity())
                        .withDirectEntity(body.entity())
                        .withDamageLocation(from)
                        .build());
            }
            double dealt = damage;
            ctx.him().log().debug(() -> "hunt: hit " + p.getName() + " for " + dealt + ", health now " + p.getHealth());
            Vector push = p.getLocation().toVector().subtract(from.toVector()).setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                p.setVelocity(p.getVelocity().add(push.normalize().multiply(knock).setY(0.28)));
            }
            if (learns()) {
                ctx.session().memory().chase().recordHitLanded();
            }
            if (elite) {
                // Hit, then step back out of reach, like a player who knows what they are doing.
                backoffUntil = ctx.him().tick() + (HuntGear.isSpear(weapon) ? 12 : 6);
            }
        }

        /** Moves the carrier, unseen, to a hidden spot around the player. Counted by the brain. */
        private void reposition(Player p) {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hidden(p, 10, 20, 180, 120);
            if (spot.isEmpty() || Sight.canSee(p, spot.get().feet(), Sight.PERIPHERAL_HALF_ANGLE)) {
                return;
            }
            carrier.teleport(spot.get().feet());
            body.moveTo(spot.get().feet());
            brain.onRepositioned();
            ctx.him().log().debug(() -> "hunt: hidden reposition for " + p.getName());
        }

        /**
         * The player landed after flying. If they got away from him he is already near, out of view;
         * the chase goes on from there. Not counted as one of the limited stuck repositions.
         */
        private void land(Player p, Location carrierLoc) {
            watchingFromBelow = false;
            if (carrierLoc.getWorld() == p.getWorld() && carrierLoc.distance(p.getLocation()) < 20) {
                return;
            }
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(p, 10, 20);
            if (spot.isEmpty() || Sight.canSee(p, spot.get().feet(), Sight.PERIPHERAL_HALF_ANGLE)) {
                return;
            }
            hideBody();
            carrier.teleport(spot.get().feet());
            body.moveTo(spot.get().feet());
            ctx.him().log().debug(() -> "hunt: " + p.getName() + " landed, he is already there");
        }

        /**
         * Once per hunt, a little while into the flight: he stands on the ground far ahead of the player,
         * off to the side and never where they are looking straight at, face turned up at them. At that
         * distance he appears the way anything far away loads in. He is gone again once they have passed.
         */
        private void watchFromBelow(long tick, Player p, Location carrierLoc) {
            if (watchingFromBelow) {
                Location pl = p.getLocation();
                Vector toHim = carrierLoc.toVector().subtract(pl.toVector()).setY(0);
                // The server does not know a player's real velocity; use the tracked movement instead.
                Vector heading = new Vector(targetVelocity.x(), 0, targetVelocity.z());
                boolean passed = heading.lengthSquared() > 1.0E-4 && toHim.dot(heading) < 0 && toHim.length() > 24;
                if (passed || toHim.length() > 96) {
                    watchingFromBelow = false;
                }
                return;
            }
            if (watchedFromBelow || !ctx.him().config().chase().flight().watchFromBelow()
                    || brain.mode() != ChaseBrain.Mode.AIRBORNE || ctx.him().random().nextDouble() > 0.05) {
                return;
            }
            Vector v = new Vector(targetVelocity.x(), 0, targetVelocity.z());
            if (v.lengthSquared() < 0.25) {
                return;
            }
            Location pl = p.getLocation();
            Vector side = new Vector(-v.getZ(), 0, v.getX()).normalize();
            for (int attempt = 0; attempt < 6; attempt++) {
                double ahead = 70 + ctx.him().random().nextDouble() * 40;
                double off = (ctx.him().random().nextBoolean() ? 1 : -1) * (15 + ctx.him().random().nextDouble() * 20);
                Vector at = pl.toVector().add(v.clone().normalize().multiply(ahead)).add(side.clone().multiply(off));
                int x = at.getBlockX();
                int z = at.getBlockZ();
                if (!p.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                int y = p.getWorld().getHighestBlockYAt(x, z) + 1;
                Optional<Location> ground = Ground.findStandable(p.getWorld(), x, y, z, 2, 4);
                if (ground.isEmpty() || ground.get().getY() > pl.getY() - 6
                        || ground.get().distance(pl) < 64 || Sight.canSee(p, ground.get(), Sight.FOCUS_HALF_ANGLE * 2)) {
                    continue;
                }
                hideBody();
                carrier.teleport(ground.get());
                body.moveTo(ground.get());
                watchingFromBelow = true;
                watchedFromBelow = true;
                ctx.him().log().debug(() -> "hunt: watching " + p.getName() + " fly over");
                return;
            }
        }

        private void openDoorsAhead(Location at, long tick) {
            float yaw = at.getYaw();
            double[] dir = MathUtil.horizontalDirection(yaw);
            for (int dy = 0; dy <= 1; dy++) {
                Block b = at.clone().add(dir[0] * 0.9, dy, dir[1] * 0.9).getBlock();
                if ((Tag.WOODEN_DOORS.isTagged(b.getType()) || Tag.FENCE_GATES.isTagged(b.getType()))
                        && b.getBlockData() instanceof Openable o && !o.isOpen()) {
                    o.setOpen(true);
                    // Journaled, so a crash mid-hunt cannot leave the door open for good.
                    if (!ctx.him().journal().apply(b, o, ctx.him().clock().now() + 60_000L, "hunt_door")) {
                        continue;
                    }
                    SoundCues.play(viewers, b.getLocation().add(0.5, 0.5, 0.5),
                            Tag.FENCE_GATES.isTagged(b.getType()) ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN,
                            SoundCategory.BLOCKS, 1.0f, 0.8f);
                    openedDoors.put(b, tick);
                }
            }
        }

        private void closeDoorsBehind(long tick) {
            openedDoors.entrySet().removeIf(e -> {
                if (tick - e.getValue() < 60) {
                    return false;
                }
                closeDoor(e.getKey());
                return true;
            });
        }

        private void closeDoor(Block b) {
            // Restores the recorded (closed) state unless someone changed the door since.
            ctx.him().journal().restoreNow(b);
        }

        private void end(ChaseBrain.EndReason reason) {
            if (endReason != null) {
                return;
            }
            endReason = reason;
            Player p = ctx.target();
            PlayerMemory memory = !learns() ? null
                    : ctx.session() != null ? ctx.session().memory() : ctx.him().memory().get(p.getUniqueId());
            Location pl = p.getLocation();
            UUID id = p.getUniqueId();
            long now = ctx.him().clock().now();
            Outcome outcome;
            switch (reason) {
                case CAUGHT -> {
                    outcome = Outcome.CAUGHT;
                    if (memory != null) {
                        memory.chase().recordCaught();
                    }
                    addLandmark(new Landmark(Landmark.Kind.DEATH_SITE, id, pl.getWorld().getName(), pl.getBlockX(),
                            pl.getBlockY(), pl.getBlockZ(), now));
                }
                case REPELLED -> {
                    outcome = Outcome.REPELLED;
                    if (memory != null) {
                        memory.chase().recordRepelled();
                    }
                    addLandmark(new Landmark(Landmark.Kind.ATTACK_SITE, id, pl.getWorld().getName(), pl.getBlockX(),
                            pl.getBlockY(), pl.getBlockZ(), now));
                }
                case SLAIN -> {
                    outcome = Outcome.SLAIN;
                    if (memory != null) {
                        memory.chase().recordSlain();
                    }
                    Location at = body.location();
                    addLandmark(new Landmark(Landmark.Kind.ATTACK_SITE, id, at.getWorld().getName(), at.getBlockX(),
                            at.getBlockY(), at.getBlockZ(), now));
                    dropHead(at);
                }
                case OUTRAN, LOST_SIGHT, SHELTERED, TIMEOUT, FLEW, TELEPORTED -> {
                    outcome = Outcome.ESCAPED;
                    EscapeMethod method = switch (reason) {
                        case OUTRAN -> EscapeMethod.OUTRAN;
                        case SHELTERED -> EscapeMethod.SHELTER;
                        case TIMEOUT -> EscapeMethod.TIMEOUT;
                        case FLEW -> EscapeMethod.FLEW;
                        case TELEPORTED -> EscapeMethod.TELEPORT;
                        default -> p.isInWater() ? EscapeMethod.WATER : EscapeMethod.BROKE_SIGHT;
                    };
                    if (memory != null) {
                        memory.chase().recordEscape(method);
                    }
                    addLandmark(new Landmark(Landmark.Kind.ESCAPE_SITE, id, pl.getWorld().getName(), pl.getBlockX(),
                            pl.getBlockY(), pl.getBlockZ(), now));
                }
                case TARGET_GONE -> {
                    outcome = Outcome.LOGGED_OUT;
                    if (memory != null) {
                        memory.chase().recordEscape(EscapeMethod.LOGGED_OUT);
                        Landmark mark = new Landmark(Landmark.Kind.LOGOUT_SITE, id, pl.getWorld().getName(),
                                pl.getBlockX(), pl.getBlockY(), pl.getBlockZ(), now);
                        memory.setPendingReturn(mark);
                        addLandmark(mark);
                    }
                }
                default -> outcome = Outcome.INTERRUPTED;
            }
            setOutcome(id, outcome);
            ctx.him().log().debug(() -> "hunt of " + p.getName() + " ended: " + reason + " (" + hits + " hits taken, "
                    + brain.repositionsUsed() + " repositions, " + (siege == null ? 0 : siege.used()) + " siege blocks)");
            if (reason.playerWon() && bodyShown && p.isOnline() && body.valid()) {
                // He does not just blink out in front of them: he stands and watches, then is gone.
                carrier.stop();
                aftermathSince = ctx.him().tick();
                return;
            }
            finish(outcome);
        }

        /** A hit pushes him back a little, further for a heavy or sprinting blow. Never into a wall. */
        private void knockBack(Player attacker, double damage) {
            if (leapStart >= 0 || riptideStart >= 0 || aftermathSince >= 0) {
                return;
            }
            Location me = carrier.location();
            Vector away = me.toVector().subtract(attacker.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                return;
            }
            double push = 0.5 + (attacker.isSprinting() || damage >= 8 ? 0.4 : 0);
            Location to = me.clone().add(away.normalize().multiply(push));
            if (to.getBlock().isPassable() && to.clone().add(0, 1, 0).getBlock().isPassable()
                    && !to.clone().subtract(0, 0.5, 0).getBlock().isPassable()) {
                carrier.teleport(to);
                body.moveTo(to);
            }
        }

        /** Rarely, he leaves his head behind. */
        private void dropHead(Location at) {
            if (ctx.him().random().nextDouble() >= ctx.him().config().chase().combat().headDropChance()) {
                return;
            }
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.setData(DataComponentTypes.PROFILE, ctx.him().entities().skinProfile());
            head.setData(DataComponentTypes.ITEM_NAME, Component.text("HIM 的頭", NamedTextColor.WHITE));
            head.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                    Component.text("他記得是誰。", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))));
            at.getWorld().dropItemNaturally(at.clone().add(0, 0.5, 0), head);
            ctx.him().log().debug(() -> "hunt: he left his head behind at " + at.toVector());
        }

        /** Killed: he goes down, lies still, and is gone in smoke. */
        private void dying(long tick) {
            Mannequin m = body.entity();
            long t = tick - aftermathSince;
            if (!deathShown) {
                deathShown = true;
                carrier.stop();
                m.getEquipment().setItem(EquipmentSlot.HAND, null, true);
                if (Mannequin.validPoses().contains(Pose.SLEEPING)) {
                    m.setPose(Pose.SLEEPING, true);
                }
                SoundCues.play(viewers, m.getLocation(), Sound.ENTITY_PLAYER_DEATH, SoundCategory.HOSTILE, 1.0f, 0.5f);
                SoundCues.play(viewers, m.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.8f, 0.5f);
            }
            if (t % 4 == 0) {
                for (Player v : viewers) {
                    if (v.isOnline() && v.getWorld() == m.getWorld()) {
                        v.spawnParticle(Particle.LARGE_SMOKE, m.getLocation().add(0, 0.3, 0), 6, 0.6, 0.15, 0.3, 0.01);
                    }
                }
            }
            if (t >= 40) {
                for (Player v : viewers) {
                    if (v.isOnline() && v.getWorld() == m.getWorld()) {
                        v.spawnParticle(Particle.LARGE_SMOKE, m.getLocation().add(0, 0.4, 0), 40, 0.5, 0.4, 0.5, 0.03);
                    }
                }
                finish(outcome());
            }
        }

        private void aftermath(long tick) {
            Player p = ctx.target();
            if (!body.valid() || !p.isOnline() || p.getWorld() != body.location().getWorld()) {
                finish(outcome());
                return;
            }
            if (endReason == ChaseBrain.EndReason.SLAIN) {
                dying(tick);
                return;
            }
            body.lookAt(p.getEyeLocation(), false);
            boolean seen = Sight.canSee(p, body.location(), Sight.PERIPHERAL_HALF_ANGLE);
            unseenTicks = seen ? 0 : unseenTicks + 1;
            if (unseenTicks >= 5 || tick - aftermathSince > AFTERMATH_MAX) {
                finish(outcome());
            }
        }

        @Override
        protected boolean owns(HimBody b) {
            return b == body;
        }

        @Override
        protected void onBodyHit(HimBody b, Player attacker, double damage) {
            var combat = ctx.him().config().chase().combat();
            long now = ctx.him().tick();
            if (endReason != null || now - lastHurtAt < 10) {
                // Already over, or still flinching from the last hit (as anyone would be).
                return;
            }
            lastHurtAt = now;
            lastHitByPlayerAt = now;
            hits++;
            if (learns()) {
                ctx.session().memory().chase().recordHitTaken();
            }
            // The brain reacts with a stagger (a step back) on its next tick.
            if (!combat.killable()) {
                return;
            }
            knockBack(attacker, damage);
            double taken = HuntGear.damageTaken(damage, armorCounted, combat.armorReductionPerPiece());
            health -= taken;
            Location at = body.location();
            body.entity().playHurtAnimation(0);
            SoundCues.play(viewers, at, Sound.ENTITY_PLAYER_HURT, SoundCategory.HOSTILE, 1.0f, 0.5f);
            ctx.him().log().debug(() -> String.format(Locale.ROOT, "hunt: %s hit him for %.1f (%.1f taken), %.1f/%.0f left",
                    attacker.getName(), damage, taken, Math.max(0, health), maxHealth));
            if (bossBar != null) {
                bossBar.progress((float) Math.max(0, Math.min(1, health / maxHealth)));
            }
            var eliteCfg = ctx.him().config().chase().elite();
            if (health > 0 && elite && eliteCfg.minions() && !minionsSummoned && loadout.tier() >= eliteCfg.minionMinTier()
                    && health < maxHealth * eliteCfg.minionBelowHealth()) {
                summonMinions(attacker);
            }
            if (health <= 0) {
                end(ChaseBrain.EndReason.SLAIN);
            } else if (health < maxHealth * combat.fleeBelow() && ctx.him().random().nextDouble() < combat.fleeChance()) {
                // Badly hurt, he backs off. He will remember this.
                end(ChaseBrain.EndReason.REPELLED);
            }
        }

        @Override
        protected boolean retains(UUID player) {
            return arriveAt >= 0 && player.equals(ctx.target().getUniqueId());
        }

        @Override
        protected void onParticipantLost(UUID player, LossReason reason) {
            if (!player.equals(ctx.target().getUniqueId())) {
                if (body != null) {
                    body.forgetViewer(player);
                }
                if (viewers != null) {
                    viewers.removeIf(v -> v.getUniqueId().equals(player));
                }
                return;
            }
            switch (reason) {
                case TELEPORT, WORLD_CHANGE -> {
                    if (arriveAt >= 0) {
                        return;
                    }
                    if (aftermathSince < 0 && follows < ctx.him().config().chase().teleport().follows()) {
                        // Teleporting away does not shake him off: he will be there shortly.
                        follows++;
                        hideBody();
                        carrier.stop();
                        long now = ctx.him().tick();
                        arriveAt = now + 60 + ctx.him().random().nextInt(80);
                        followDeadline = arriveAt + 200;
                        ctx.him().log().debug(() -> "hunt: " + ctx.target().getName() + " teleported away, following");
                        return;
                    }
                    end(ChaseBrain.EndReason.TELEPORTED);
                }
                case PORTAL -> end(ChaseBrain.EndReason.TELEPORTED);
                case QUIT -> end(ChaseBrain.EndReason.TARGET_GONE);
                case DEATH -> end(ctx.him().consumeKilledByHim(player) ? ChaseBrain.EndReason.CAUGHT
                        : ChaseBrain.EndReason.ABORTED);
                default -> end(ChaseBrain.EndReason.ABORTED);
            }
            finish(outcome());
        }

        @Override
        protected void cleanup() {
            hideBossBar();
            dismissMinions();
            if (siege != null && viewers != null) {
                siege.stop(viewers);
            }
            for (Block b : openedDoors.keySet()) {
                closeDoor(b);
            }
            openedDoors.clear();
            ctx.him().entities().remove(body);
            ctx.him().entities().remove(carrier);
        }

        @Override
        protected int maxTicks() {
            return settings == null ? 20 * 120 : settings.maxDurationTicks() + AFTERMATH_MAX + 200;
        }
    }
}
