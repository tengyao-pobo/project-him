package dev.projecthim.him.event.impl;

import dev.projecthim.him.chase.ChaseBrain;
import dev.projecthim.him.chase.ChaseSettings;
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
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import dev.projecthim.him.util.MathUtil;
import dev.projecthim.him.util.Vec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.bukkit.util.Vector;

/**
 * The hunt. A hidden carrier mob walks the real terrain with vanilla navigation, the visible body
 * follows it, and {@link ChaseBrain} decides what to do every tick. HIM adapts to the player's past:
 * players who escape through water meet a better swimmer, players who outrun him meet a slightly
 * faster one, players who hide get searched for longer.
 *
 * <p>Fairness rules: no teleporting while observed, at most a few hidden repositions, configurable
 * damage and lethality, a hard time limit, and several clear ways to escape.
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

        private ChaseSettings settings;
        private ChaseBrain brain;
        private HimCarrier carrier;
        private HimBody body;
        private List<Player> viewers;
        private boolean bodyShown;
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
            Optional<SpotFinder.Spot> spot = revealed
                    ? spots.distant(p, 12, 20, false)
                    : spots.hiddenOrBehind(p, 22, 34);
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
            showBody();
            // Players who keep escaping through water meet a better swimmer.
            waterFactor = memory.chase().favouriteEscape() == EscapeMethod.WATER ? 0.85 : 0.5;
            double aggression = ctx.him().memory().world().personality().get(Trait.AGGRESSION);
            brain = new ChaseBrain(settings, ctx.him().random(), revealed, 0.95 + 0.1 * aggression);
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

            if ((tick & 1) == 0) {
                Location himEye = carrierLoc.clone().add(0, 1.62, 0);
                // He perceives the player within a limited range, like anyone would.
                himSeesTarget = himEye.distanceSquared(p.getEyeLocation()) <= SIGHT_RANGE * SIGHT_RANGE
                        && Sight.visibility(carrierLoc.getWorld(), himEye.toVector(), p.getEyeLocation().toVector()) >= 0.4;
                targetSeesHim = Sight.canSee(p, carrierLoc, Sight.PERIPHERAL_HALF_ANGLE);
            }
            carrierTrail.add(vec(carrierLoc));
            if (carrierTrail.size() > 40) {
                carrierTrail.removeFirst();
            }
            boolean progressing = carrierTrail.size() < 40
                    || carrierTrail.getFirst().distance(carrierTrail.getLast()) > 1.5;
            boolean sheltered = p.getLocation().getBlock().getLightFromSky() <= 3
                    && p.getWorld().getHighestBlockYAt(pl) > pl.getBlockY() + 1
                    && p.getLocation().getBlock().getLightFromBlocks() >= 8;

            ChaseBrain.Command cmd = brain.tick(new ChaseBrain.Inputs(tick, vec(carrierLoc), targetPos, targetVelocity,
                    himSeesTarget, targetSeesHim, p.isInWater(), sheltered, progressing, hits, p.isDead(),
                    !p.isOnline()));
            if (cmd.end() != null) {
                end(cmd.end());
                return;
            }
            execute(cmd, tick, p, carrierLoc);
        }

        private void execute(ChaseBrain.Command cmd, long tick, Player p, Location carrierLoc) {
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
                if (settings.openDoors()) {
                    openDoorsAhead(carrierLoc, tick);
                }
            } else {
                carrier.stop();
            }
            closeDoorsBehind(tick);

            if (cmd.bodyVisible() && !bodyShown) {
                // Reappear only where nobody is looking.
                if (!targetSeesHim) {
                    showBody();
                }
            } else if (!cmd.bodyVisible() && bodyShown) {
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
                case OUTRAN, LOST_SIGHT, SHELTERED, TIMEOUT -> {
                    outcome = Outcome.ESCAPED;
                    EscapeMethod method = switch (reason) {
                        case OUTRAN -> EscapeMethod.OUTRAN;
                        case SHELTERED -> EscapeMethod.SHELTER;
                        case TIMEOUT -> EscapeMethod.TIMEOUT;
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
                    + brain.repositionsUsed() + " repositions)");
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
                case QUIT -> end(ChaseBrain.EndReason.TARGET_GONE);
                case DEATH -> end(ctx.him().consumeKilledByHim(player) ? ChaseBrain.EndReason.CAUGHT
                        : ChaseBrain.EndReason.ABORTED);
                default -> end(ChaseBrain.EndReason.ABORTED);
            }
            finish(outcome());
        }

        @Override
        protected void cleanup() {
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
