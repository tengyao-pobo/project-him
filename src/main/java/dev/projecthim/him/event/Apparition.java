package dev.projecthim.him.event;

import dev.projecthim.him.Him;
import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.util.MathUtil;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * A visible appearance of HIM plus everything needed to read the players' reactions to it: who can
 * see him, who is looking straight at him and for how long, who walked closer, who ran.
 *
 * <p>Movement is a slow, ground-following glide (used for stepping behind cover, walking away or
 * approaching); long distance navigation is the hunt controller's job.
 */
public final class Apparition {

    public enum Look {
        /** Head tracks the nearest viewer, body lags behind. */
        STARE,
        /** Faces the walking direction. */
        WALK,
        /** Keeps the current rotation. */
        FIXED
    }

    /** Per viewer perception state. */
    public static final class View {
        final UUID id;
        boolean inView;
        boolean looking;
        int lookingTicks;
        int totalLookTicks;
        boolean noticed;
        long noticedTick = -1;
        int awayTicksAfterNotice;
        double startDistance = -1;
        double minDistance = Double.MAX_VALUE;
        double lastDistance;
        double distanceAtNotice;
        boolean sprintedAway;

        View(UUID id) {
            this.id = id;
        }

        public UUID id() {
            return id;
        }

        public boolean inView() {
            return inView;
        }

        public boolean looking() {
            return looking;
        }

        public int lookingTicks() {
            return lookingTicks;
        }

        public boolean noticed() {
            return noticed;
        }

        public int awayTicksAfterNotice() {
            return awayTicksAfterNotice;
        }

        public double distance() {
            return lastDistance;
        }
    }

    /** Continuous looking for this many ticks counts as "noticed". */
    private static final int NOTICE_TICKS = 5;

    private final Him him;
    private final HimBody body;
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private Look look = Look.STARE;
    private Location walkTarget;
    private double walkSpeed;
    private boolean gone;
    private boolean concealed;
    private final java.util.Set<UUID> attackers = new java.util.HashSet<>();

    private Apparition(Him him, HimBody body) {
        this.him = him;
        this.body = body;
    }

    /**
     * Spawns an apparition visible to exactly the given viewers. Returns empty if no apparition slot
     * is free or the spawn was refused.
     */
    public static Optional<Apparition> spawn(Him him, Location feet, Collection<? extends Player> viewers) {
        return spawn(him, feet, viewers, false);
    }

    /**
     * Like {@link #spawn(Him, Location, Collection)}; with {@code concealed} the viewers are registered
     * but the body is not sent to anyone until {@link #reveal()}.
     */
    public static Optional<Apparition> spawn(Him him, Location feet, Collection<? extends Player> viewers,
                                             boolean concealed) {
        if (!him.canSpawnApparition()) {
            return Optional.empty();
        }
        Location at = feet.clone();
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : viewers) {
            double d = p.getLocation().distanceSquared(at);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest != null) {
            at.setYaw(MathUtil.yawTowards(at.getX(), at.getZ(), nearest.getX(), nearest.getZ()));
        }
        HimBody body = him.entities().spawnBody(at);
        if (body == null) {
            return Optional.empty();
        }
        Apparition a = new Apparition(him, body);
        a.concealed = concealed;
        for (Player p : viewers) {
            if (!concealed) {
                body.show(p);
            }
            a.views.put(p.getUniqueId(), new View(p.getUniqueId()));
        }
        if (nearest != null) {
            body.lookAt(nearest.getEyeLocation(), true);
        }
        return Optional.of(a);
    }

    public void tick(long tick) {
        if (gone) {
            return;
        }
        if (!body.valid()) {
            gone = true;
            return;
        }
        if (concealed) {
            return;
        }
        Location feet = body.location();
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (View v : views.values()) {
            Player p = Bukkit.getPlayer(v.id);
            if (p == null || p.getWorld() != feet.getWorld()) {
                v.inView = false;
                v.looking = false;
                continue;
            }
            double d = p.getLocation().distance(feet);
            if (v.startDistance < 0) {
                v.startDistance = d;
            }
            v.minDistance = Math.min(v.minDistance, d);
            v.lastDistance = d;
            if (d < nearestDist) {
                nearestDist = d;
                nearest = p;
            }
            // Visibility costs ray traces: evaluate every other tick.
            if ((tick & 1) == 0) {
                double exposure = Sight.exposure(p.getEyeLocation(), feet);
                double angle = Math.min(Sight.viewAngle(p, feet.clone().add(0, 1.62, 0)),
                        Sight.viewAngle(p, feet.clone().add(0, 1.0, 0)));
                v.inView = exposure >= 0.35 && angle <= Sight.PERIPHERAL_HALF_ANGLE;
                v.looking = exposure >= 0.35 && angle <= Sight.FOCUS_HALF_ANGLE + Math.min(8, 40 / Math.max(1, d));
            }
            if (v.looking) {
                v.lookingTicks++;
                v.totalLookTicks++;
                if (!v.noticed && v.lookingTicks >= NOTICE_TICKS) {
                    v.noticed = true;
                    v.noticedTick = tick;
                    v.distanceAtNotice = d;
                }
                v.awayTicksAfterNotice = 0;
            } else {
                v.lookingTicks = 0;
                if (v.noticed) {
                    v.awayTicksAfterNotice++;
                }
            }
            if (v.noticed && p.isSprinting() && d > v.distanceAtNotice + 4) {
                v.sprintedAway = true;
            }
        }

        if (walkTarget != null) {
            stepTowardsTarget(feet);
        }
        switch (look) {
            case STARE -> {
                if (nearest != null) {
                    body.lookAt(nearest.getEyeLocation(), false);
                }
            }
            case WALK, FIXED -> {
                // Rotation handled by movement or left alone.
            }
        }
    }

    private void stepTowardsTarget(Location feet) {
        Vector delta = walkTarget.toVector().subtract(feet.toVector()).setY(0);
        double step = walkSpeed / 20.0;
        double dist = delta.length();
        if (dist <= step) {
            Location arrive = walkTarget.clone();
            arrive.setYaw(feet.getYaw());
            arrive.setPitch(feet.getPitch());
            body.moveTo(arrive);
            walkTarget = null;
            return;
        }
        delta.multiply(step / dist);
        Location next = feet.clone().add(delta);
        Optional<Location> ground = Ground.findStandable(feet.getWorld(), next.getBlockX(), feet.getBlockY(),
                next.getBlockZ(), 1, 2);
        if (ground.isEmpty()) {
            // Blocked: stop rather than clip through terrain.
            walkTarget = null;
            return;
        }
        next.setY(ground.get().getY());
        float yaw = MathUtil.yawTowards(feet.getX(), feet.getZ(), walkTarget.getX(), walkTarget.getZ());
        if (look == Look.WALK) {
            next.setYaw(yaw);
            next.setPitch(0);
            body.moveTo(next);
            body.entity().setBodyYaw(yaw);
        } else {
            next.setYaw(feet.getYaw());
            next.setPitch(feet.getPitch());
            body.moveTo(next);
        }
    }

    public void walkTo(Location target, double blocksPerSecond, Look whileWalking) {
        this.walkTarget = target.clone();
        this.walkSpeed = blocksPerSecond;
        this.look = whileWalking;
    }

    public boolean walking() {
        return walkTarget != null;
    }

    public void setLook(Look look) {
        this.look = look;
    }

    public void lookAt(Location point) {
        look = Look.FIXED;
        body.lookAt(point, true);
    }

    /** Removes the body immediately (no effect: he is simply not there any more). */
    public void vanish() {
        if (!gone) {
            gone = true;
            him.entities().remove(body);
        }
    }

    public boolean gone() {
        return gone || !body.valid();
    }

    public HimBody body() {
        return body;
    }

    public Location location() {
        return body.location();
    }

    public View view(UUID id) {
        return views.get(id);
    }

    public Collection<View> views() {
        return views.values();
    }

    public void forgetViewer(UUID id) {
        views.remove(id);
        body.forgetViewer(id);
    }

    public boolean anyNoticed() {
        return views.values().stream().anyMatch(v -> v.noticed);
    }

    public boolean anyInView() {
        return views.values().stream().anyMatch(v -> v.inView);
    }

    public boolean anyLookingFor(int ticks) {
        return views.values().stream().anyMatch(v -> v.lookingTicks >= ticks);
    }

    public double nearestDistance() {
        return views.values().stream().mapToDouble(v -> v.lastDistance).min().orElse(Double.MAX_VALUE);
    }

    public void markAttacked(UUID attacker) {
        attackers.add(attacker);
    }

    /** Hides the body from all viewers without removing it; perception is paused meanwhile. */
    public void conceal() {
        concealed = true;
        body.hideAll();
        for (View v : views.values()) {
            v.inView = false;
            v.looking = false;
            v.lookingTicks = 0;
        }
    }

    /** Shows the body again to the original viewers that are still online. */
    public void reveal() {
        concealed = false;
        for (View v : views.values()) {
            Player p = Bukkit.getPlayer(v.id);
            if (p != null) {
                body.show(p);
            }
        }
    }

    public boolean concealed() {
        return concealed;
    }

    /** Moves the body instantly. Only call while concealed or out of every viewer's sight. */
    public void relocate(Location feet) {
        walkTarget = null;
        body.moveTo(feet);
    }

    /** The reaction of one viewer, derived from what they did while HIM was there. */
    public Outcome reactionOf(UUID id) {
        View v = views.get(id);
        if (v == null) {
            return Outcome.UNNOTICED;
        }
        if (attackers.contains(id)) {
            return Outcome.ATTACKED;
        }
        if (!v.noticed) {
            return Outcome.UNNOTICED;
        }
        if (v.sprintedAway) {
            return Outcome.FLED;
        }
        if (v.minDistance < v.distanceAtNotice - 5) {
            return Outcome.APPROACHED;
        }
        return Outcome.NOTICED;
    }
}
