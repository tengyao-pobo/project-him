package dev.projecthim.him.chase;

import dev.projecthim.him.memory.ChaseStats;
import dev.projecthim.him.memory.ChaseStats.EscapeMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.bukkit.Material;

/**
 * What HIM carries into a hunt. In adaptive mode the gear follows the player's record against him:
 * a newcomer meets the classic empty-handed figure; a player who keeps escaping or beating him back
 * meets one who came prepared. The context picks the tool (a pickaxe underground), the player's
 * habits pick the weapon (a bow and slowing potions for runners, an axe or a mace for fighters),
 * and sometimes he comes one tier lighter than expected so the gear never becomes fully predictable.
 *
 * <p>Tier 4 is reserved for players who beat him again and again: netherite, shining with an
 * enchantment glint. The glint is only a look; what the gear does in a fight is capped by the
 * controller so fighting back always stays a way out.
 */
public final class HuntGear {

    public static final int MAX_TIER = 4;

    /**
     * Item per slot; null means empty.
     *
     * @param ranged   a bow he may switch to, or null
     * @param potions  whether he carries splash potions
     * @param glint    the enchantment shine (no real enchantments)
     * @param tier     0 (empty-handed) to 4 (netherite); 3 and up fights like a skilled player
     */
    public record Loadout(Material mainHand, Material offHand, Material helmet, Material chestplate,
                          Material leggings, Material boots, Material ranged, boolean potions, boolean glint,
                          int tier) {

        public static final Loadout NONE = new Loadout(null, null, null, null, null, null, null, false, false, 0);

        /** A hand-picked loadout, ranked by the best material in it. */
        public static Loadout fixed(Material mainHand, Material offHand, Material helmet, Material chestplate,
                                    Material leggings, Material boots, Material ranged, boolean potions,
                                    boolean glint) {
            int tier = 0;
            for (Material m : new Material[] {mainHand, helmet, chestplate, leggings, boots}) {
                if (m == null) {
                    continue;
                }
                String n = m.name();
                tier = Math.max(tier, n.startsWith("NETHERITE_") ? 4 : n.startsWith("DIAMOND_") || m == Material.MACE ? 3
                        : n.startsWith("IRON_") ? 2 : 1);
            }
            return new Loadout(mainHand, offHand, helmet, chestplate, leggings, boots, ranged, potions, glint, tier);
        }

        public int armorPieces() {
            int n = 0;
            for (Material m : new Material[] {helmet, chestplate, leggings, boots}) {
                if (m != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Which kinds of weapons the server allows. */
    public record Arsenal(boolean bow, boolean potions, boolean spears, boolean maces, boolean shields) {

        public static final Arsenal ALL = new Arsenal(true, true, true, true, true);
    }

    private HuntGear() {
    }

    /** 0 (nothing) to 4 (netherite), from how often the player got the better of him. */
    public static int tier(ChaseStats stats) {
        int threat = stats.slain() * 3 + stats.repelled() * 2 + stats.escapes();
        if (threat < 2) {
            return 0;
        }
        if (threat < 5) {
            return 1;
        }
        if (threat < 9) {
            return 2;
        }
        if (threat < 14) {
            return 3;
        }
        return 4;
    }

    public static Loadout adaptive(ChaseStats stats, boolean underground, RandomGenerator random) {
        return adaptive(stats, underground, random, MAX_TIER, true, Arsenal.ALL);
    }

    /**
     * @param maxTier  the highest tier he may reach on this server
     * @param glint    whether tier 4 shines with an enchantment glint
     */
    public static Loadout adaptive(ChaseStats stats, boolean underground, RandomGenerator random, int maxTier,
                                   boolean glint, Arsenal arsenal) {
        int tier = Math.min(tier(stats), Math.max(0, maxTier));
        if (tier > 0 && random.nextDouble() < 0.2) {
            tier--;
        }
        return ofTier(tier, stats, underground, random, glint, arsenal);
    }

    /**
     * A loadout of exactly this tier (the weapons still chosen by context and habit), for example
     * when an administrator asks for one.
     */
    public static Loadout ofTier(int tier, ChaseStats stats, boolean underground, RandomGenerator random, boolean glint,
                                 Arsenal arsenal) {
        tier = Math.max(0, Math.min(MAX_TIER, tier));
        if (tier == 0) {
            return Loadout.NONE;
        }
        EscapeMethod habit = stats.favouriteEscape();
        // Players who pearl away count as runners too: they meet the bow more often.
        boolean runner = habit == EscapeMethod.OUTRAN || habit == EscapeMethod.FLEW || habit == EscapeMethod.BROKE_SIGHT
                || stats.pearls() >= 3;
        boolean fighter = habit == EscapeMethod.FOUGHT || stats.repelled() + stats.slain() * 2 > stats.escapes();
        Material melee = melee(tier, underground, fighter, arsenal, random);
        Material ranged = arsenal.bow() && tier >= 2 && random.nextDouble() < (runner ? 0.7 : 0.35) ? Material.BOW : null;
        boolean potions = arsenal.potions() && tier >= 2 && random.nextDouble() < (runner ? 0.5 : 0.3);
        // A shield in the other hand, from iron on: more often for fighters, who keep hitting him.
        Material shield = arsenal.shields() && tier >= 2 && random.nextDouble() < (fighter ? 0.6 : 0.4)
                ? Material.SHIELD : null;
        return switch (tier) {
            case 1 -> new Loadout(melee, null, null, null, null, null, null, false, false, 1);
            case 2 -> new Loadout(melee, shield, null, Material.IRON_CHESTPLATE, null, null, ranged, potions, false, 2);
            case 3 -> new Loadout(melee, shield, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                    Material.IRON_BOOTS, ranged, potions, false, 3);
            default -> new Loadout(melee, shield, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, ranged, potions, glint, 4);
        };
    }

    private static Material melee(int tier, boolean underground, boolean fighter, Arsenal arsenal, RandomGenerator r) {
        if (underground && (tier == 1 || r.nextDouble() < 0.4)) {
            return tier >= 4 ? Material.NETHERITE_PICKAXE : tier == 3 ? Material.DIAMOND_PICKAXE : Material.IRON_PICKAXE;
        }
        String metal = switch (tier) {
            case 1 -> "STONE";
            case 2 -> "IRON";
            case 3 -> "DIAMOND";
            default -> "NETHERITE";
        };
        // Fighters meet what beats a fighter: the axe (breaks shields) and the mace.
        List<Material> pool = new ArrayList<>();
        pool.add(Material.valueOf(metal + "_SWORD"));
        pool.add(Material.valueOf(metal + "_AXE"));
        if (fighter) {
            pool.add(Material.valueOf(metal + "_AXE"));
        }
        if (arsenal.spears()) {
            pool.add(Material.valueOf(metal + "_SPEAR"));
        }
        if (arsenal.maces() && tier >= 3) {
            pool.add(Material.MACE);
            if (fighter) {
                pool.add(Material.MACE);
            }
        }
        return pool.get(r.nextInt(pool.size()));
    }

    /** The kinds of tools blocks call for. */
    public enum ToolKind {
        PICKAXE, AXE, SHOVEL, HOE, SHEARS
    }

    /** The tool he takes out to break a block, made of the material that matches his gear tier. */
    public static Material tool(ToolKind kind, int tier) {
        if (kind == ToolKind.SHEARS) {
            return Material.SHEARS;
        }
        String metal = switch (Math.max(0, Math.min(MAX_TIER, tier))) {
            case 0, 1 -> "STONE";
            case 2 -> "IRON";
            case 3 -> "DIAMOND";
            default -> "NETHERITE";
        };
        return Material.valueOf(metal + "_" + kind.name());
    }

    /** Extra damage a held item adds to HIM's base attack. */
    public static double damageBonus(Material weapon) {
        if (weapon == null) {
            return 0;
        }
        String n = weapon.name();
        double tier = n.startsWith("NETHERITE_") ? 4 : n.startsWith("DIAMOND_") ? 3 : n.startsWith("IRON_") ? 2
                : n.startsWith("STONE_") || n.startsWith("COPPER_") ? 1 : 0;
        if (n.endsWith("_SWORD") || n.endsWith("_SPEAR")) {
            return tier * 0.5;
        }
        if (n.endsWith("_AXE")) {
            return 0.5 + tier * 0.5;
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) {
            return tier * 0.25;
        }
        if (n.equals("TRIDENT") || n.equals("MACE")) {
            return 2;
        }
        return 0;
    }

    public static boolean isAxe(Material m) {
        return m != null && m.name().endsWith("_AXE") && !m.name().endsWith("_PICKAXE");
    }

    public static boolean isSpear(Material m) {
        return m != null && m.name().endsWith("_SPEAR");
    }

    public static boolean isMace(Material m) {
        return m == Material.MACE;
    }

    /** Extra reach: a spear keeps players at a distance. */
    public static double reachBonus(Material m) {
        return isSpear(m) ? 1.0 : 0;
    }

    /** Extra damage of a mace blow from above, by how far he came down. */
    public static double smashBonus(Material m, double heightAbove) {
        if (!isMace(m) || heightAbove < 1.5) {
            return 0;
        }
        return Math.min(6, 2 + (heightAbove - 1.5) * 1.5);
    }

    /**
     * Height of a wind-charge leap at fraction {@code t} (0..1) of its flight: a parabola peaking
     * at {@code apex} above the straight line between take-off and landing.
     */
    public static double leapHeight(double t, double apex) {
        double c = Math.max(0, Math.min(1, t));
        return 4 * apex * c * (1 - c);
    }

    /** Damage he actually takes from a hit, after his armour. */
    public static double damageTaken(double raw, int armorPieces, double reductionPerPiece) {
        return Math.max(0, raw) * Math.max(0, 1 - armorPieces * reductionPerPiece);
    }

    /**
     * Where to aim an arrow so it lands at a target {@code distance} blocks away: arrows fall about
     * 0.05 blocks per tick per tick.
     */
    public static double arrowLift(double distance, double speed) {
        return lift(distance, speed, 0.05);
    }

    /** The same for anything thrown, with its own gravity (an ender pearl falls 0.03 per tick per tick). */
    public static double lift(double distance, double speed, double gravity) {
        double ticks = distance / speed;
        return 0.5 * gravity * ticks * ticks;
    }
}
