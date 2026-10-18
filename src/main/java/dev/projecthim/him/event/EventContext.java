package dev.projecthim.him.event;

import dev.projecthim.him.Him;
import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.player.PlayerSession;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Everything an event instance needs: the services, who it targets, how it was chosen.
 *
 * @param participants all players taking part, the target first
 */
public record EventContext(Him him, EventSpec spec, String instanceId, Player target, List<Player> participants,
                           EncounterMode mode, boolean subvert, Trigger trigger) {

    public PlayerSession session() {
        return him.sessions().get(target.getUniqueId());
    }

    public PlayerSession session(Player player) {
        return him.sessions().get(player.getUniqueId());
    }

    public boolean isParticipant(UUID id) {
        for (Player p : participants) {
            if (p.getUniqueId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Participants other than the target. */
    public List<Player> others() {
        return participants.stream().filter(p -> !p.getUniqueId().equals(target.getUniqueId())).toList();
    }
}
