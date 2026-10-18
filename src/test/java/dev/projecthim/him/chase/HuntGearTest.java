package dev.projecthim.him.chase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            HuntGear.Loadout l = HuntGear.adaptive(legend, false, r, 4, true, HuntGear.Arsenal.ALL);
            if (l.chestplate() == Material.NETHERITE_CHESTPLATE) {
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
            assertTrue(HuntGear.adaptive(legend, false, r, 3, true, HuntGear.Arsenal.ALL).tier() <= 3);
            assertFalse(HuntGear.adaptive(legend, false, r, 4, false, HuntGear.Arsenal.ALL).glint());
            assertSame(HuntGear.Loadout.NONE, HuntGear.adaptive(legend, false, r, 0, true, HuntGear.Arsenal.ALL));
        }
        assertEquals(2.0, HuntGear.damageBonus(Material.NETHERITE_SWORD));
    }

    @Test
    void runnersMeetTheBowAndFightersMeetTheAxe() {
        ChaseStats runner = new ChaseStats();
        for (int i = 0; i < 10; i++) {
            runner.recordEscape(ChaseStats.EscapeMethod.OUTRAN);
        }
        ChaseStats fighter = new ChaseStats();
        for (int i = 0; i < 5; i++) {
            fighter.recordRepelled();
        }
        SplittableRandom r = new SplittableRandom(6);
        int runnerBows = 0;
        int fighterBows = 0;
        int fighterHeavy = 0;
        for (int i = 0; i < 1000; i++) {
            if (HuntGear.adaptive(runner, false, r).ranged() == Material.BOW) {
                runnerBows++;
            }
            HuntGear.Loadout f = HuntGear.adaptive(fighter, false, r);
            if (f.ranged() == Material.BOW) {
                fighterBows++;
            }
            if (HuntGear.isAxe(f.mainHand()) || HuntGear.isMace(f.mainHand())) {
                fighterHeavy++;
            }
        }
        assertTrue(runnerBows > fighterBows + 150, runnerBows + " vs " + fighterBows);
        assertTrue(fighterHeavy > 450, "fighters mostly meet axes and maces: " + fighterHeavy);
    }

    @Test
    void theServerDecidesWhichWeaponsExist() {
        ChaseStats veteran = new ChaseStats();
        for (int i = 0; i < 5; i++) {
            veteran.recordRepelled();
        }
        HuntGear.Arsenal swordsOnly = new HuntGear.Arsenal(false, false, false, false);
        SplittableRandom r = new SplittableRandom(7);
        for (int i = 0; i < 300; i++) {
            HuntGear.Loadout l = HuntGear.adaptive(veteran, false, r, 4, true, swordsOnly);
            assertNull(l.ranged());
            assertFalse(l.potions());
            assertFalse(HuntGear.isSpear(l.mainHand()) || HuntGear.isMace(l.mainHand()), String.valueOf(l.mainHand()));
        }
    }

    @Test
    void weaponMechanics() {
        assertFalse(HuntGear.isAxe(Material.IRON_PICKAXE));
        assertTrue(HuntGear.isAxe(Material.DIAMOND_AXE));
        assertEquals(1.0, HuntGear.reachBonus(Material.IRON_SPEAR));
        assertEquals(0, HuntGear.reachBonus(Material.IRON_SWORD));
        assertEquals(0, HuntGear.smashBonus(Material.MACE, 1.0), "a mace needs height");
        assertTrue(HuntGear.smashBonus(Material.MACE, 5) > HuntGear.smashBonus(Material.MACE, 2));
        assertEquals(6, HuntGear.smashBonus(Material.MACE, 100), "capped");
        assertEquals(0, HuntGear.smashBonus(Material.IRON_SWORD, 5));
        assertEquals(0, HuntGear.leapHeight(0, 5));
        assertEquals(5, HuntGear.leapHeight(0.5, 5), 1.0E-9);
        assertEquals(0, HuntGear.leapHeight(1, 5), 1.0E-9);
        assertEquals(10 * 0.76, HuntGear.damageTaken(10, 3, 0.08), 1.0E-9);
        assertEquals(0, HuntGear.damageTaken(-3, 0, 0.08));
        assertTrue(HuntGear.arrowLift(20, 2.6) > HuntGear.arrowLift(10, 2.6));
        assertEquals(4, HuntGear.Loadout.fixed(Material.IRON_SWORD, null, null, Material.NETHERITE_CHESTPLATE, null,
                null, null, false, false).tier());
        assertEquals(0, HuntGear.Loadout.fixed(null, null, null, null, null, null, null, false, false).tier());
    }

    @Test
    void toolsMatchHisGear() {
        assertEquals(Material.STONE_PICKAXE, HuntGear.tool(HuntGear.ToolKind.PICKAXE, 0));
        assertEquals(Material.IRON_AXE, HuntGear.tool(HuntGear.ToolKind.AXE, 2));
        assertEquals(Material.DIAMOND_SHOVEL, HuntGear.tool(HuntGear.ToolKind.SHOVEL, 3));
        assertEquals(Material.NETHERITE_HOE, HuntGear.tool(HuntGear.ToolKind.HOE, 4));
        assertEquals(Material.SHEARS, HuntGear.tool(HuntGear.ToolKind.SHEARS, 4));
    }

    @Test
    void anExactTierIsNeverLowered() {
        SplittableRandom r = new SplittableRandom(8);
        for (int t = 0; t <= 4; t++) {
            for (int i = 0; i < 100; i++) {
                assertEquals(t, HuntGear.ofTier(t, new ChaseStats(), false, r, true, HuntGear.Arsenal.ALL).tier());
            }
        }
        assertEquals(4, HuntGear.ofTier(9, new ChaseStats(), false, r, true, HuntGear.Arsenal.ALL).tier());
    }
}
