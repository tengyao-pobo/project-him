package dev.projecthim.him.chase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.memory.ChaseStats;
import java.util.SplittableRandom;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class HuntGearTest {

    @Test
    void newcomersMeetTheEmptyHandedClassic() {
        ChaseStats fresh = new ChaseStats();
        assertEquals(0, HuntGear.tier(fresh));
        assertSame(HuntGear.Loadout.NONE, HuntGear.adaptive(fresh, false, new SplittableRandom(1)));
    }

    @Test
    void gearGrowsWithThePlayersRecord() {
        ChaseStats veteran = new ChaseStats();
        for (int i = 0; i < 5; i++) {
            veteran.recordRepelled();
        }
        assertEquals(3, HuntGear.tier(veteran));
        int armored = 0;
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 200; i++) {
            HuntGear.Loadout l = HuntGear.adaptive(veteran, false, r);
            if (l.armorPieces() == 4) {
                armored++;
            }
            assertTrue(l.mainHand() != null, "a veteran always meets an armed HIM");
        }
        // Mostly fully armed, but sometimes one tier lighter.
        assertTrue(armored > 120 && armored < 200, "armored " + armored);
    }

    @Test
    void contextPicksTheTool() {
        ChaseStats s = new ChaseStats();
        s.recordEscape(ChaseStats.EscapeMethod.OUTRAN);
        s.recordEscape(ChaseStats.EscapeMethod.OUTRAN);
        s.recordEscape(ChaseStats.EscapeMethod.OUTRAN);
        assertEquals(1, HuntGear.tier(s));
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 50; i++) {
            HuntGear.Loadout l = HuntGear.adaptive(s, true, r);
            assertTrue(l.mainHand() == null || l.mainHand() == Material.IRON_PICKAXE, "underground means a pickaxe");
        }
    }

    @Test
    void weaponsAddModestDamage() {
        assertEquals(0, HuntGear.damageBonus(null));
        assertEquals(1.0, HuntGear.damageBonus(Material.IRON_SWORD));
        assertEquals(1.5, HuntGear.damageBonus(Material.DIAMOND_SWORD));
        assertTrue(HuntGear.damageBonus(Material.IRON_AXE) > HuntGear.damageBonus(Material.IRON_SWORD));
        assertEquals(0, HuntGear.damageBonus(Material.STICK));
    }

    @Test
    void onlyPlayersWhoKeepBeatingHimMeetTheShiningNetherite() {
        ChaseStats legend = new ChaseStats();
        for (int i = 0; i < 7; i++) {
            legend.recordRepelled();
        }
        assertEquals(4, HuntGear.tier(legend));
        SplittableRandom r = new SplittableRandom(5);
        int netherite = 0;
        for (int i = 0; i < 200; i++) {
            HuntGear.Loadout l = HuntGear.adaptive(legend, false, r, 4, true);
            if (l.mainHand() == Material.NETHERITE_SWORD) {
                netherite++;
                assertTrue(l.glint());
                assertEquals(4, l.armorPieces());
            } else {
                assertFalse(l.glint(), "only tier 4 shines");
            }
        }
        assertTrue(netherite > 120 && netherite < 200, "netherite " + netherite);
        // The server can cap the tiers, or keep the netherite without the shine.
        for (int i = 0; i < 50; i++) {
            assertTrue(HuntGear.adaptive(legend, false, r, 3, true).mainHand() != Material.NETHERITE_SWORD);
            assertFalse(HuntGear.adaptive(legend, false, r, 4, false).glint());
            assertSame(HuntGear.Loadout.NONE, HuntGear.adaptive(legend, false, r, 0, true));
        }
        assertEquals(2.0, HuntGear.damageBonus(Material.NETHERITE_SWORD));
    }
}
