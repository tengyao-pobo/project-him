package dev.projecthim.him.director;

import java.util.UUID;

/**
 * Immutable snapshot of a player's situation, sampled on the server thread and consumed by the
 * platform independent director logic.
 *
 * @param distanceToBase distance to the estimated base, or {@link Double#NaN} if unknown
 * @param speed          horizontal speed in blocks per second (smoothed)
 * @param nearbyPlayers  other eligible players within the group radius
 */
public record PlayerContext(
        UUID playerId,
        String world,
        Dimension dimension,
        boolean night,
        boolean raining,
        boolean thundering,
        int light,
        boolean skyVisible,
        boolean underground,
        boolean inWater,
        boolean inVehicle,
        boolean airborne,
        boolean sleeping,
        double health,
        double speed,
        int idleSeconds,
        boolean sprinting,
        int nearbyPlayers,
        boolean inCombat,
        double distanceToBase,
        double baseConfidence,
        boolean sheltered,
        boolean nearLandmark,
        long sessionSeconds) {

    public enum Dimension {
        OVERWORLD, NETHER, END, OTHER
    }

    public boolean alone() {
        return nearbyPlayers == 0;
    }

    public boolean dark() {
        return light <= 6;
    }

    public boolean baseKnown() {
        return !Double.isNaN(distanceToBase) && baseConfidence >= 0.25;
    }

    public boolean atBase(double radius) {
        return baseKnown() && distanceToBase <= radius;
    }

    /** Outdoors at night or in a storm: the classic sighting weather. */
    public boolean exposedAtNight() {
        return skyVisible && (night || thundering);
    }

    /** Situations where any visual or audio horror would be unfair or would glitch. */
    public boolean busy() {
        return inVehicle || airborne || sleeping || inCombat;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(UUID id, String world) {
        return new Builder(id, world);
    }

    /** Builder mainly for tests and the sampler. */
    public static final class Builder {
        private UUID playerId;
        private String world;
        private Dimension dimension = Dimension.OVERWORLD;
        private boolean night;
        private boolean raining;
        private boolean thundering;
        private int light = 15;
        private boolean skyVisible = true;
        private boolean underground;
        private boolean inWater;
        private boolean inVehicle;
        private boolean airborne;
        private boolean sleeping;
        private double health = 1.0;
        private double speed;
        private int idleSeconds;
        private boolean sprinting;
        private int nearbyPlayers;
        private boolean inCombat;
        private double distanceToBase = Double.NaN;
        private double baseConfidence;
        private boolean sheltered;
        private boolean nearLandmark;
        private long sessionSeconds = 3600;

        private Builder(UUID id, String world) {
            this.playerId = id;
            this.world = world;
        }

        private Builder(PlayerContext c) {
            playerId = c.playerId;
            world = c.world;
            dimension = c.dimension;
            night = c.night;
            raining = c.raining;
            thundering = c.thundering;
            light = c.light;
            skyVisible = c.skyVisible;
            underground = c.underground;
            inWater = c.inWater;
            inVehicle = c.inVehicle;
            airborne = c.airborne;
            sleeping = c.sleeping;
            health = c.health;
            speed = c.speed;
            idleSeconds = c.idleSeconds;
            sprinting = c.sprinting;
            nearbyPlayers = c.nearbyPlayers;
            inCombat = c.inCombat;
            distanceToBase = c.distanceToBase;
            baseConfidence = c.baseConfidence;
            sheltered = c.sheltered;
            nearLandmark = c.nearLandmark;
            sessionSeconds = c.sessionSeconds;
        }

        public Builder dimension(Dimension v) { dimension = v; return this; }
        public Builder night(boolean v) { night = v; return this; }
        public Builder raining(boolean v) { raining = v; return this; }
        public Builder thundering(boolean v) { thundering = v; return this; }
        public Builder light(int v) { light = v; return this; }
        public Builder skyVisible(boolean v) { skyVisible = v; return this; }
        public Builder underground(boolean v) { underground = v; return this; }
        public Builder inWater(boolean v) { inWater = v; return this; }
        public Builder inVehicle(boolean v) { inVehicle = v; return this; }
        public Builder airborne(boolean v) { airborne = v; return this; }
        public Builder sleeping(boolean v) { sleeping = v; return this; }
        public Builder health(double v) { health = v; return this; }
        public Builder speed(double v) { speed = v; return this; }
        public Builder idleSeconds(int v) { idleSeconds = v; return this; }
        public Builder sprinting(boolean v) { sprinting = v; return this; }
        public Builder nearbyPlayers(int v) { nearbyPlayers = v; return this; }
        public Builder inCombat(boolean v) { inCombat = v; return this; }
        public Builder distanceToBase(double v) { distanceToBase = v; return this; }
        public Builder baseConfidence(double v) { baseConfidence = v; return this; }
        public Builder sheltered(boolean v) { sheltered = v; return this; }
        public Builder nearLandmark(boolean v) { nearLandmark = v; return this; }
        public Builder sessionSeconds(long v) { sessionSeconds = v; return this; }

        public PlayerContext build() {
            return new PlayerContext(playerId, world, dimension, night, raining, thundering, light, skyVisible,
                    underground, inWater, inVehicle, airborne, sleeping, health, speed, idleSeconds, sprinting,
                    nearbyPlayers, inCombat, distanceToBase, baseConfidence, sheltered, nearLandmark, sessionSeconds);
        }
    }
}
