package dev.projecthim.him.event.impl;

import dev.projecthim.him.Him;
import dev.projecthim.him.chase.HuntGear;
import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.entity.HimCarrier;
import dev.projecthim.him.sense.Ground;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * What HIM does during a siege, one action at a time, at a pace players can see and react to:
 * <ol>
 *     <li>Player on a pillar within reach: crack and break the block they stand on.</li>
 *     <li>Player on a pillar out of reach: build up next to them, one block per action.</li>
 *     <li>Otherwise: crack and break the first block between him and the player.</li>
 * </ol>
 * Every change goes through the world change journal (restored later while nobody is near), is
 * offered to protection plugins as an {@link EntityChangeBlockEvent} first, never touches containers
 * or other block entities, and is limited per hunt. When the budget is spent he simply waits below.
 */
final class SiegeWorks {

    private static final double REACH = 4.5;

    private final Him him;
    private final HimConfig.Siege cfg;
    private final String owner;
    private final int crackSource;
    private final Set<Block> refused = new HashSet<>();
    /** His gear tier and glint (tools match his gear), and how to put his weapon back in his hand. */
    private final int tier;
    private final boolean glint;
    private final Runnable restoreHand;
    private int used;
    private Block working;
    private int progress;
    /** What he took out for the work (a tool or a block), or null while his weapon is in hand. */
    private Material holding;
    private long releaseAt;

    SiegeWorks(Him him, HimConfig.Siege cfg, String owner, int tier, boolean glint, Runnable restoreHand) {
        this.him = him;
        this.cfg = cfg;
        this.owner = owner;
        this.tier = tier;
        this.glint = glint;
        this.restoreHand = restoreHand;
        this.crackSource = 0x5E000000 | him.random().nextInt(0xFFFFFF);
    }

    /** The right tool for a block, as a player would pick it. */
    private static HuntGear.ToolKind toolKind(Material m) {
        if (Tag.LEAVES.isTagged(m) || Tag.WOOL.isTagged(m) || m == Material.COBWEB) {
            return HuntGear.ToolKind.SHEARS;
        }
        if (Tag.MINEABLE_AXE.isTagged(m)) {
            return HuntGear.ToolKind.AXE;
        }
        if (Tag.MINEABLE_SHOVEL.isTagged(m)) {
            return HuntGear.ToolKind.SHOVEL;
        }
        if (Tag.MINEABLE_HOE.isTagged(m)) {
            return HuntGear.ToolKind.HOE;
        }
        return HuntGear.ToolKind.PICKAXE;
    }

    private void hold(HimBody body, Material item, long tick) {
        releaseAt = tick + 12;
        if (item == holding) {
            return;
        }
        holding = item;
        ItemStack stack = new ItemStack(item);
        if (glint && item.name().startsWith("NETHERITE_")) {
            stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        body.entity().getEquipment().setItem(EquipmentSlot.HAND, stack, true);
    }

    /** Something else took his hand (a bow, the trident): nothing of the work is in it any more. */
    void handTaken() {
        holding = null;
    }

    /** Puts the weapon back once there has been nothing to work on for a moment. */
    void idle(long tick) {
        if (holding != null && working == null && tick >= releaseAt) {
            release();
        }
    }

    private void release() {
        if (holding != null) {
            holding = null;
            restoreHand.run();
        }
    }

    int used() {
        return used;
    }

    /**
     * Advances the current action.
     *
     * @return true if the world changed (the carrier should plan a new path)
     */
    boolean tick(long tick, HimCarrier carrier, HimBody body, Player target, List<Player> viewers) {
        if (used >= cfg.maxBlocks() || !griefingAllowed(target.getWorld())) {
            return false;
        }
        if (working != null) {
            hold(body, HuntGear.tool(toolKind(working.getType()), tier), tick);
            return continueBreaking(tick, body, viewers);
        }
        Location pos = carrier.location();
        Location t = target.getLocation();
        int dy = t.getBlockY() - pos.getBlockY();
        double horizontal = Math.hypot(t.getX() - pos.getX(), t.getZ() - pos.getZ());
        if (dy >= 2 && horizontal <= 3.5) {
            Block under = t.clone().subtract(0, 0.2, 0).getBlock();
            Location eye = pos.clone().add(0, 1.62, 0);
            if (cfg.breakBlocks() && eye.distance(under.getLocation().add(0.5, 0.5, 0.5)) <= REACH && breakable(under, body)) {
                startBreaking(under);
                return false;
            }
            if (cfg.buildUp() && horizontal <= 2.5) {
                return buildUp(tick, carrier, body, viewers);
            }
            return false;
        }
        if (cfg.breakBlocks()) {
            Block blocking = firstObstacle(pos, t);
            if (blocking != null && breakable(blocking, body)) {
                startBreaking(blocking);
            }
        }
        return false;
    }

    private boolean griefingAllowed(World world) {
        if (!him.config().worldEdits().respectMobGriefing()) {
            return true;
        }
        Boolean griefing = world.getGameRuleValue(GameRules.MOB_GRIEFING);
        return griefing == null || griefing;
    }

    private void startBreaking(Block b) {
        working = b;
        progress = 0;
    }

    private boolean continueBreaking(long tick, HimBody body, List<Player> viewers) {
        Block b = working;
        if (b.getType().isAir() || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
            clearCrack(b, viewers);
            working = null;
            return true;
        }
        progress++;
        float stage = Math.min(0.95f, progress / (float) cfg.ticksPerBlock());
        for (Player v : viewers) {
            if (v.isOnline()) {
                him.fakes().crack(v, b.getLocation(), stage, crackSource, 40, tick, owner);
            }
        }
        if (progress % 4 == 1) {
            body.entity().swingMainHand();
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), b.getBlockData().getSoundGroup().getHitSound(),
                    SoundCategory.HOSTILE, 0.9f, 0.8f);
        }
        if (progress < cfg.ticksPerBlock()) {
            return false;
        }
        working = null;
        clearCrack(b, viewers);
        if (!breakable(b, body)) {
            return false;
        }
        BlockData before = b.getBlockData();
        long revertAt = him.clock().now() + (long) (him.config().worldEdits().revertAfterMinutes() * 60_000L);
        if (!him.journal().apply(b, Material.AIR.createBlockData(), revertAt, "siege_break")) {
            refused.add(b);
            return false;
        }
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().playSound(center, before.getSoundGroup().getBreakSound(), SoundCategory.HOSTILE, 1.0f, 0.8f);
        b.getWorld().spawnParticle(Particle.BLOCK, center, 24, 0.3, 0.3, 0.3, before);
        used++;
        return true;
    }

    private void clearCrack(Block b, List<Player> viewers) {
        for (Player v : viewers) {
            if (v.isOnline() && v.getWorld() == b.getWorld()) {
                v.sendBlockDamage(b.getLocation(), 0f, crackSource);
            }
        }
    }

    /** Places a block where he stands and rises onto it. */
    private boolean buildUp(long tick, HimCarrier carrier, HimBody body, List<Player> viewers) {
        Location pos = carrier.location();
        Block feet = pos.getBlock();
        World w = feet.getWorld();
        Material material = Material.matchMaterial(cfg.buildMaterial());
        if (material == null || !material.isSolid()) {
            material = Material.NETHERRACK;
        }
        if (!Ground.free(feet.getType()) || feet.isLiquid()
                || !Ground.free(w.getType(feet.getX(), feet.getY() + 2, feet.getZ()))) {
            return false;
        }
        // The block he is about to place is in his hand, as it would be for anyone.
        hold(body, material, tick);
        BlockData data = material.createBlockData();
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(body.entity(), feet, data);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        long revertAt = him.clock().now() + (long) (him.config().worldEdits().revertAfterMinutes() * 60_000L);
        if (!him.journal().apply(feet, data, revertAt, "siege_build")) {
            return false;
        }
        Location up = pos.clone().add(0, 1, 0);
        carrier.teleport(up);
        body.moveTo(up);
        body.entity().swingMainHand();
        w.playSound(feet.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getPlaceSound(), SoundCategory.HOSTILE,
                1.0f, 0.8f);
        used++;
        return true;
    }

    /** First non-passable block on the straight line between HIM and the player, at feet or head height. */
    private Block firstObstacle(Location from, Location to) {
        World w = from.getWorld();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) {
            return null;
        }
        int steps = (int) Math.min(5.0 / 0.4, len / 0.4);
        for (int i = 1; i <= steps; i++) {
            double f = i * 0.4 / len;
            double x = from.getX() + dx * f;
            double y = from.getY() + dy * f;
            double z = from.getZ() + dz * f;
            for (int k = 1; k >= 0; k--) {
                Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y) + k, (int) Math.floor(z));
                if (!Ground.free(b.getType()) && !b.getType().name().endsWith("_DOOR")) {
                    return b;
                }
            }
        }
        return null;
    }

    private boolean breakable(Block b, HimBody body) {
        if (refused.contains(b) || b.getType().isAir() || b.isLiquid() || him.journal().contains(b)) {
            return false;
        }
        BlockType type = b.getType().asBlockType();
        if (type == null) {
            return false;
        }
        float hardness = type.getHardness();
        if (hardness < 0 || hardness > cfg.maxHardness() || b.getState(false) instanceof TileState) {
            refused.add(b);
            return false;
        }
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(body.entity(), b, Material.AIR.createBlockData());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            refused.add(b);
            return false;
        }
        return true;
    }

    /** Cancels a crack in progress (hunt ended). */
    void stop(List<Player> viewers) {
        if (working != null) {
            clearCrack(working, viewers);
            working = null;
        }
        release();
    }
}
