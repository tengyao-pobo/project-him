package dev.projecthim.him.event.impl;

import dev.projecthim.him.memory.EncounterRecord;
import dev.projecthim.him.memory.PlayerMemory;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Formats configured sign texts with the player's own history. */
final class Texts {

    private Texts() {
    }

    static List<Component> signLines(String template, Player player, PlayerMemory memory, long now) {
        long days = Math.max(1, (now - memory.firstSeen()) / 86_400_000L);
        List<EncounterRecord> recent = memory.recentEncounters(1);
        String x = recent.isEmpty() ? "?" : String.valueOf(recent.getFirst().x());
        String z = recent.isEmpty() ? "?" : String.valueOf(recent.getFirst().z());
        String text = template.replace("{player}", player.getName())
                .replace("{days}", String.valueOf(days))
                .replace("{x}", x)
                .replace("{z}", z);
        String[] parts = text.split("\\|", -1);
        List<Component> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            lines.add(Component.text(i < parts.length ? parts[i] : ""));
        }
        return lines;
    }
}
