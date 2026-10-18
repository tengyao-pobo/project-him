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
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
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
            showBody();
            siege = new SiegeWorks(ctx.him(), ctx.him().config().chase().siege(), ctx.instanceId());
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

        /** Dresses the body for the hunt and lets the gear shape damage and toughness. */
        private void equip(PlayerMemory memory, Player p) {
            var gearCfg = ctx.him().config().chase().gear();
            loadout = switch (gearCfg.mode()) {
                case NONE -> HuntGear.Loadout.NONE;
                case FIXED -> fixedLoadout(gearCfg.fixed());
                case ADAPTIVE -> HuntGear.adaptive(memory.chase(),
                        ctx.session().context() != null && ctx.session().context().underground(), ctx.him().random());
            };
            dress();
            double bonus = gearCfg.weaponAffectsDamage() ? HuntGear.damageBonus(loadout.mainHand()) : 0;
            int armor = gearCfg.armorAffectsRepel() ? loadout.armorPieces() : 0;
            if (bonus > 0 || armor > 0) {
                ChaseSettings s = settings;
                settings = new ChaseSettings(s.walkSpeed(), s.runSpeed(), s.burstSpeed(), s.burstTicks(),
                        s.burstCooldownTicks(), s.attackRange(), s.attackDamage() + bonus, s.attackCooldownTicks(),
                        s.lethal(), s.maxDurationTicks(), s.revealTicks(), s.loseSightTicks(), s.searchTicks(),
                        s.escapeDistance(), s.hitsToRepel() + armor, s.fakeRetreatChance(), s.maxRepositions(),
                        s.openDoors());
            }
            HuntGear.Loadout chosen = loadout;
            ctx.him().log().debug(() -> "hunt gear for " + p.getName() + ": " + chosen);
        }

        private void dress() {
            var eq = body.entity().getEquipment();
            eq.setItem(EquipmentSlot.HAND, item(loadout.mainHand()), true);
            eq.setItem(EquipmentSlot.OFF_HAND, item(loadout.offHand()), true);
            eq.setItem(EquipmentSlot.HEAD, item(loadout.helmet()), true);
            eq.setItem(EquipmentSlot.CHEST, item(loadout.chestplate()), true);
            eq.setItem(EquipmentSlot.LEGS, item(loadout.leggings()), true);
            eq.setItem(EquipmentSlot.FEET, item(loadout.boots()), true);
        }

        private static HuntGear.Loadout fixedLoadout(java.util.Map<String, String> fixed) {
            return new HuntGear.Loadout(material(fixed.get("main-hand")), material(fixed.get("off-hand")),
                    material(fixed.get("helmet")), material(fixed.get("chestplate")), material(fixed.get("leggings")),
                    material(fixed.get("boots")));
        }

        private static org.bukkit.Material material(String name) {
            return name == null ? null : org.bukkit.Material.matchMaterial(name);
        }

        private static ItemStack item(org.bukkit.Material m) {
            return m == null || !m.isItem() ? null : new ItemStack(m);
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
            if (cmd.speed() > 0 && cmd.destination() != null) {
                boolean swimming = carrierLoc.getBlock().getType() == org.bukkit.Material.WATER;
                carrier.setSpeed(cmd.speed() * (swimming ? waterFactor : 1.0));
                Location dest = new Location(carrierLoc.getWorld(), cmd.destination().x(), cmd.destination().y(),
                        cmd.destination().z());
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
            if (cmd.attackAllowed() && tick >= nextAttackAt && bodyShown && himSeesTarget) {
                double horizontal = Math.hypot(follow.getX() - p.getX(), follow.getZ() - p.getZ());
                if (horizontal <= settings.attackRange() && Math.abs(follow.getY() - p.getY()) < 2.0) {
                    attack(p, follow);
                    nextAttackAt = tick + settings.attackCooldownTicks();
                }
            }
        }

        private void attack(Player p, Location from) {
            body.entity().swingMainHand();
            double damage = settings.attackDamage();
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
                p.setVelocity(p.getVelocity().add(push.normalize().multiply(0.45).setY(0.28)));
            }
            if (learns()) {
                ctx.session().memory().chase().recordHitLanded();
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

        private void aftermath(long tick) {
            Player p = ctx.target();
            if (!body.valid() || !p.isOnline() || p.getWorld() != body.location().getWorld()) {
                finish(outcome());
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
        protected void onBodyHit(HimBody b, Player attacker) {
            hits++;
            if (learns()) {
                ctx.session().memory().chase().recordHitTaken();
            }
            // The brain reacts with a stagger (a step back) on its next tick.
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
