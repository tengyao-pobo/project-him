package dev.projecthim.him.chase;

/**
 * Hunt tuning. Speeds are in blocks per second; for reference a sprinting player moves at about 5.6
 * and walks at about 4.3. Defaults keep HIM slightly slower than a sprinting player so a hunt is
 * survivable, with short bursts that punish hesitation.
 */
public record ChaseSettings(
        double walkSpeed,
        double runSpeed,
        double burstSpeed,
        int burstTicks,
        int burstCooldownTicks,
        double attackRange,
        double attackDamage,
        int attackCooldownTicks,
        boolean lethal,
        int maxDurationTicks,
        int revealTicks,
        int loseSightTicks,
        int searchTicks,
        double escapeDistance,
        int hitsToRepel,
        double fakeRetreatChance,
        int maxRepositions,
        boolean openDoors) {

    public static ChaseSettings defaults() {
        return new ChaseSettings(3.0, 5.0, 6.6, 26, 160, 1.9, 5.0, 24, true, 20 * 100, 36, 70, 20 * 18, 40,
                6, 0.35, 2, true);
    }
}
