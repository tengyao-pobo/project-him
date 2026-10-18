package dev.projecthim.him.player;

import dev.projecthim.him.Him;
import dev.projecthim.him.memory.PlayerMemory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Tracks online players, loads their memory, samples their context, and decides who is eligible. */
public final class SessionManager {

    private final Him him;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    public SessionManager(Him him) {
        this.him = him;
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        him.memory().load(id, player.getName(), uuid -> Bukkit.getPlayer(uuid) != null, memory -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                return;
            }
            long now = him.clock().now();
            memory.onJoin(online.getName(), now);
            PlayerSession session = new PlayerSession(id, memory, now, him.random());
            sessions.put(id, session);
            him.director().onSessionStarted(online, session);
        });
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        him.events().participantLost(id, dev.projecthim.him.event.EventInstance.LossReason.QUIT);
        him.fakes().forgetPlayer(id);
        sessions.remove(id);
        him.memory().unload(id);
    }

    /** Drops the session without saving its memory (used right before deleting that memory). */
    public void quitSilently(Player player) {
        him.fakes().revertPlayer(player);
        sessions.remove(player.getUniqueId());
    }

    public PlayerSession get(UUID id) {
        return sessions.get(id);
    }

    public Collection<PlayerSession> all() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    /** Samples contexts, staggered so that each player is sampled once per period. */
    public void tick(long tick) {
        int period = him.performance().samplePeriodTicks();
        long now = him.clock().now();
        Map<World, List<Player>> eligibleByWorld = null;
        for (PlayerSession s : sessions.values()) {
            if (Math.floorMod(tick + s.id().hashCode(), period) != 0) {
                continue;
            }
            Player p = Bukkit.getPlayer(s.id());
            if (p == null) {
                continue;
            }
            if (eligibleByWorld == null) {
                eligibleByWorld = eligibleByWorld();
            }
            List<Player> sameWorld = eligibleByWorld.getOrDefault(p.getWorld(), List.of());
            ContextSampler.sample(p, s, him.memory().world(), him.config(), sameWorld, now);
            him.memory().markDirty(s.id());
        }
    }

    private Map<World, List<Player>> eligibleByWorld() {
        Map<World, List<Player>> map = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (eligible(p)) {
                map.computeIfAbsent(p.getWorld(), w -> new ArrayList<>()).add(p);
            }
        }
        return map;
    }

    /**
     * Whether HIM may target this player at all right now. Checked on every decision and before
     * anyone is added to a group encounter.
     */
    public boolean eligible(Player player) {
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null || player.isDead() || !player.isValid()) {
            return false;
        }
        var cfg = him.config();
        if (cfg.players().respectExemptPermission() && player.hasPermission("him.exempt")) {
            return false;
        }
        if (cfg.players().survivalOnly()
                && player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            return false;
        }
        PlayerMemory m = s.memory();
        boolean participating = m.participating() != null ? m.participating() : cfg.players().participateByDefault();
        if (!participating) {
            return false;
        }
        if (!him.worldAllowed(player.getWorld())) {
            return false;
        }
        return him.clock().now() - s.joinedAt() >= (long) (cfg.players().graceMinutes() * 60_000L);
    }

    /** Other eligible players near the target, nearest first, for group and shared encounters. */
    public List<Player> nearbyEligible(Player target, double radius, int max) {
        List<Player> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Player p : target.getWorld().getPlayers()) {
            if (p != target && eligible(p) && !him.events().isLocked(p.getUniqueId())
                    && p.getLocation().distanceSquared(target.getLocation()) <= r2) {
                out.add(p);
            }
        }
        out.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(target.getLocation()),
                b.getLocation().distanceSquared(target.getLocation())));
        return out.size() > max ? out.subList(0, max) : out;
    }
}
