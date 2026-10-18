package dev.projecthim.him.platform;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Positional sounds delivered to specific players only. Every horror sound goes through here so
 * that private encounters stay private: nobody but the listed listeners receives the packet.
 */
public final class SoundCues {

    private SoundCues() {
    }

    public static void play(Player listener, Location at, Sound sound, SoundCategory category, float volume, float pitch) {
        if (listener.getWorld() == at.getWorld()) {
            listener.playSound(at, sound, category, volume, pitch);
        }
    }

    public static void play(Collection<? extends Player> listeners, Location at, Sound sound, SoundCategory category,
                            float volume, float pitch) {
        for (Player p : listeners) {
            play(p, at, sound, category, volume, pitch);
        }
    }

    /** Footstep matching the material underfoot at {@code feet}. */
    public static void footstep(Player listener, Location feet, float volume) {
        Block ground = feet.clone().subtract(0, 0.2, 0).getBlock();
        Sound step = ground.getType().isAir() ? Sound.BLOCK_STONE_STEP : ground.getBlockData().getSoundGroup().getStepSound();
        play(listener, feet, step, SoundCategory.PLAYERS, volume, 0.95f + (float) Math.random() * 0.1f);
    }

    /** Mining hit on the given block, as if someone were digging it. */
    public static void dig(Player listener, Block block, float volume) {
        play(listener, block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData().getSoundGroup().getHitSound(),
                SoundCategory.BLOCKS, volume, 0.8f);
    }

    public static void breakSound(Player listener, Block block, float volume) {
        play(listener, block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData().getSoundGroup().getBreakSound(),
                SoundCategory.BLOCKS, volume, 0.9f);
    }
}
