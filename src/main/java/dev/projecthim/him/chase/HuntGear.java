package dev.projecthim.him.chase;

import dev.projecthim.him.memory.ChaseStats;
import java.util.random.RandomGenerator;
import org.bukkit.Material;

/**
 * What HIM carries into a hunt. In adaptive mode the gear follows the player's record against him:
 * a newcomer meets the classic empty-handed figure; a player who keeps escaping or beating him back
 * meets one who came prepared. The context picks the tool (a pickaxe underground), and sometimes he
 * comes one tier lighter than expected so the gear never becomes fully predictable.
 */
public final class HuntGear {

    /** Item per slot; null means empty. */
    public record Loadout(Material mainHand, Material offHand, Material helmet, Material chestplate,
                          Material leggings, Material boots) {

        public static final Loadout NONE = new Loadout(null, null, null, null, null, null);

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

    private HuntGear() {
    }

    /** 0 (nothing) to 3 (fully armed), from how often the player got the better of him. */
    public static int tier(ChaseStats stats) {
        int threat = stats.repelled() * 2 + stats.escapes();
        if (threat < 2) {
            return 0;
        }
        if (threat < 5) {
            return 1;
        }
        if (threat < 9) {
            return 2;
        }
        return 3;
    }

    public static Loadout adaptive(ChaseStats stats, boolean underground, RandomGenerator random) {
        int tier = tier(stats);
        if (tier > 0 && random.nextDouble() < 0.2) {
            tier--;
        }
        return switch (tier) {
            case 0 -> Loadout.NONE;
            case 1 -> new Loadout(underground ? Material.IRON_PICKAXE
                    : random.nextBoolean() ? Material.IRON_AXE : Material.STONE_SWORD, null, null, null, null, null);
            case 2 -> new Loadout(underground && random.nextDouble() < 0.4 ? Material.IRON_PICKAXE : Material.IRON_SWORD,
                    null, null, Material.IRON_CHESTPLATE, null, null);
            default -> new Loadout(Material.DIAMOND_SWORD, null, Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS, Material.IRON_BOOTS);
        };
    }

    /** Extra damage a held item adds to HIM's base attack. */
    public static double damageBonus(Material weapon) {
        if (weapon == null) {
            return 0;
        }
        String n = weapon.name();
        double tier = n.startsWith("NETHERITE_") ? 4 : n.startsWith("DIAMOND_") ? 3 : n.startsWith("IRON_") ? 2
                : n.startsWith("STONE_") || n.startsWith("COPPER_") ? 1 : 0;
        if (n.endsWith("_SWORD")) {
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
}
