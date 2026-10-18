package dev.projecthim.him.event.impl;

import dev.projecthim.him.Him;
import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.StageCalculator;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.EventManager;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.player.PlayerSession;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * HIM wearing a friend. The target sees another player, usually the one they play with most, with
 * that player's skin, name and gear, while the real friend is far away or in another world. Nobody
 * else sees anything; when the target asks "why are you standing over there?", the friend has no
 * idea what they are talking about.
 *
 * <ul>
 *     <li>Watching: the friend stands perfectly still in the distance, staring. Players never stand
 *     still like that.</li>
 *     <li>Beckoning: the friend walks away, stops, turns around and looks back, as if waiting to
 *     be followed, then walks on.</li>
 *     <li>Subverted: up close the disguise drops: the skin becomes HIM's and the name disappears. At
 *     high stages that can be the start of a hunt.</li>
 * </ul>
 * Players with {@code him.no-impersonate}, and players who do not take part in HIM, are never used.
 */
final class FalseFriendEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("false_friend", Intent.STALK)
            .stage(Stage.WATCHED)
            .intensity(0.55)
            .cooldownMinutes(60)
            .weight(1.6)
            .modes(EncounterMode.PRIVATE)
            .visual()
            .requires(c -> !c.inWater() && !c.sheltered())
            .fit(c -> (c.alone() ? 1.5 : 0.6) * (c.night() || c.dark() ? 1.3 : 1.0))
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        Him him = ctx.him();
        if (!him.config().impersonation().enabled()) {
            return null;
        }
        Player friend = chooseFriend(him, ctx.target(), ctx.session().memory());
        return friend == null ? null : new Instance(ctx, friend);
    }

    /**
     * The player to impersonate: the target's closest companion if possible, otherwise any player who
     * may be impersonated. The real player must be far away (or elsewhere) and out of the target's sight.
     */
    static Player chooseFriend(Him him, Player target, PlayerMemory memory) {
        List<Player> allowed = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != target && mayImpersonate(him, p, target)) {
                allowed.add(p);
            }
        }
        if (allowed.isEmpty()) {
            return null;
        }
        UUID closest = memory.closestCompanion();
        for (Player p : allowed) {
            if (p.getUniqueId().equals(closest)) {
                return p;
            }
        }
        return allowed.get(him.random().nextInt(allowed.size()));
    }

    private static boolean mayImpersonate(Him him, Player p, Player target) {
        if (p.hasPermission("him.no-impersonate") || p.isDead()) {
            return false;
        }
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s == null) {
            return false;
        }
        Boolean participating = s.memory().participating();
        if (participating == null ? !him.config().players().participateByDefault() : !participating) {
            return false;
        }
        if (p.getWorld() != target.getWorld()) {
            return true;
        }
        double min = him.config().impersonation().minDistanceFromReal();
        return p.getLocation().distanceSquared(target.getLocation()) >= min * min
                && !Sight.canSee(target, p.getLocation(), Sight.PERIPHERAL_HALF_ANGLE);
    }

    private enum Act { WATCH, BECKON, UNMASKED }

    private static final class Instance extends VisualInstance {
        private final Player friend;
        private final String friendName;
        private Apparition fake;
        private Act act;
        private long actSince;
        private int legs;
        private boolean escalate;

        Instance(EventContext ctx, Player friend) {
            super(ctx);
            this.friend = friend;
            this.friendName = friend.getName();
        }

        @Override
        protected boolean start() {
            act = ctx.him().random().nextDouble() < 0.6 ? Act.WATCH : Act.BECKON;
            SpotFinder spots = ctx.him().spots();
            Optional<SpotFinder.Spot> spot = act == Act.WATCH ? spots.distant(target(), 18, 36, false)
                    : spots.distant(target(), 12, 22, false);
            if (spot.isEmpty()) {
                return false;
            }
            ResolvableProfile disguise = ResolvableProfile.resolvableProfile(friend.getPlayerProfile());
            fake = spawn(spot.get().feet(), List.of(target()), false, disguise).orElse(null);
            if (fake == null) {
                return false;
            }
            copyGear(friend, fake.body().entity().getEquipment());
            if (ctx.him().config().impersonation().showName()) {
                fake.body().setName(friendName);
            }
            actSince = ctx.him().tick();
            if (act == Act.BECKON) {
                walkOn();
            }
            ctx.him().log().debug(() -> "false_friend: " + target().getName() + " sees " + friendName + " (" + act + ")");
            return true;
        }

        private static void copyGear(Player from, EntityEquipment to) {
            PlayerInventory inv = from.getInventory();
            to.setItem(EquipmentSlot.HAND, copy(inv.getItemInMainHand()), true);
            to.setItem(EquipmentSlot.OFF_HAND, copy(inv.getItemInOffHand()), true);
            to.setItem(EquipmentSlot.HEAD, copy(inv.getHelmet()), true);
            to.setItem(EquipmentSlot.CHEST, copy(inv.getChestplate()), true);
            to.setItem(EquipmentSlot.LEGS, copy(inv.getLeggings()), true);
            to.setItem(EquipmentSlot.FEET, copy(inv.getBoots()), true);
        }

        private static ItemStack copy(ItemStack item) {
            return item == null || item.isEmpty() ? null : item.clone();
        }

        /** Walks a stretch away from the target, facing the way he goes. */
        private void walkOn() {
            Location me = fake.location();
            Vector away = me.toVector().subtract(target().getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-6) {
                away = new Vector(1, 0, 0);
            }
            double angle = Math.toRadians((ctx.him().random().nextDouble() - 0.5) * 50);
            Vector dir = away.normalize().rotateAroundY(angle).multiply(8 + ctx.him().random().nextInt(5));
            fake.walkTo(me.clone().add(dir), 2.2, Apparition.Look.WALK);
            legs++;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            Apparition.View v = fake.view(target().getUniqueId());
            if (v == null) {
                finishWithReactions();
                return;
            }
            long inAct = tick - actSince;
            double distance = v.distance();
            switch (act) {
                case WATCH -> {
                    boolean close = distance < 9;
                    if ((close || v.lookingTicks() >= 60) && ctx.subvert()) {
                        unmask(tick);
                    } else if (close || v.noticed() && v.awayTicksAfterNotice() >= 8 || inAct > 20 * 45) {
                        vanish(fake);
                    }
                }
                case BECKON -> {
                    if (distance < 6) {
                        if (ctx.subvert()) {
                            unmask(tick);
                        } else {
                            vanish(fake);
                        }
                        return;
                    }
                    if (!fake.walking()) {
                        // Stop, turn around, look back. Walk on once followed, or give up after a while.
                        fake.setLook(Apparition.Look.STARE);
                        boolean followed = distance < 12;
                        if ((followed || inAct > 20 * 10) && legs < 4) {
                            walkOn();
                            actSince = tick;
                        } else if (legs >= 4 && !fake.anyInView() || inAct > 20 * 20) {
                            vanish(fake);
                        }
                    }
                }
                case UNMASKED -> {
                    if (inAct >= 25 || distance < 3) {
                        escalate = mayEscalate();
                        vanish(fake);
                    }
                }
            }
        }

        /** The disguise drops: HIM's own face, no name, perfectly still. */
        private void unmask(long tick) {
            fake.walkTo(fake.location(), 0.1, Apparition.Look.STARE);
            fake.body().setProfile(ctx.him().entities().skinProfile());
            fake.body().setName(null);
            fake.body().entity().getEquipment().clear();
            fake.setLook(Apparition.Look.STARE);
            act = Act.UNMASKED;
            actSince = tick;
        }

        private boolean mayEscalate() {
            Stage stage = StageCalculator.stageOf(ctx.session().memory(), ctx.him().config().director());
            if (stage.ordinal() < Stage.HUNTED.ordinal() || !ctx.him().huntSlotFree() || ctx.trigger() == Trigger.MANUAL) {
                return false;
            }
            double aggression = ctx.him().memory().world().personality().get(Trait.AGGRESSION);
            return ctx.him().random().nextDouble() < aggression * 0.6 * ctx.him().config().director().aggression();
        }

        @Override
        protected void cleanup() {
            super.cleanup();
            if (escalate) {
                Player p = target();
                Bukkit.getScheduler().runTask(ctx.him().plugin(), () -> {
                    var him = ctx.him();
                    if (p.isOnline() && !him.paused() && him.config().enabled() && him.huntSlotFree()
                            && him.sessions().eligible(p)) {
                        EventManager.LaunchResult r = him.director().launch(p, "hunt", EncounterMode.PRIVATE, true,
                                Trigger.PERIODIC);
                        him.log().debug(() -> "false_friend unmasked into a hunt for " + p.getName() + ": " + r);
                    }
                });
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 90;
        }
    }
}
