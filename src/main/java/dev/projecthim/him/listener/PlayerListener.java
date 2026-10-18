package dev.projecthim.him.listener;

import dev.projecthim.him.Him;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.EventInstance.LossReason;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.player.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Player lifecycle and behaviour signals. Lifecycle handlers guarantee that no encounter outlives
 * its participant's presence; behaviour handlers feed HIM's memory and reactive triggers.
 */
public final class PlayerListener implements Listener {

    private final Him him;

    public PlayerListener(Him him) {
        this.him = him;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        him.sessions().join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        him.sessions().quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        // Attribute the death by its actual cause, not by timing: a player HIM hit seconds ago may
        // still die from a fall, lava or a creeper.
        var killer = event.getDamageSource().getCausingEntity();
        boolean byHim = killer != null && him.entities().isOurs(killer);
        him.recordDeath(p.getUniqueId(), byHim);
        if (byHim) {
            String template = him.config().chase().deathMessage();
            if (!template.isBlank()) {
                event.deathMessage(Component.text(template.replace("{player}", p.getName())));
            }
        }
        him.events().participantLost(p.getUniqueId(), LossReason.DEATH);
        signal(p, p.getLocation(), HeatMap.Signal.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        him.events().participantLost(event.getPlayer().getUniqueId(), LossReason.WORLD_CHANGE);
    }

    /** Optionally, a hunted player cannot teleport away by command (/tp, /home, /spawn, /tpa...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleportAttempt(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if ((cause == PlayerTeleportEvent.TeleportCause.COMMAND || cause == PlayerTeleportEvent.TeleportCause.PLUGIN)
                && him.config().chase().teleport().blockCommands()
                && him.events().isHunted(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("him.bypass-teleport-block")) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("你無法離開這裡。", NamedTextColor.DARK_RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            him.events().targetPearled(event.getPlayer().getUniqueId());
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 48 * 48) {
            boolean portal = switch (event.getCause()) {
                case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
                default -> false;
            };
            him.events().participantLost(event.getPlayer().getUniqueId(), portal ? LossReason.PORTAL : LossReason.TELEPORT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignWritten(org.bukkit.event.block.SignChangeEvent event) {
        if (event.getSide() == org.bukkit.block.sign.Side.FRONT) {
            him.signs().written(event.getPlayer(), event.getBlock(), event.lines());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            Block bed = event.getBed();
            signal(event.getPlayer(), bed.getLocation(), HeatMap.Signal.SLEEP);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player p = event.getPlayer();
        // One tick later, when the player is fully awake and standing.
        Bukkit.getScheduler().runTask(him.plugin(), () -> {
            if (p.isOnline()) {
                him.director().react(p, Trigger.WAKE);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        him.fakes().forget(event.getBlock());
        Player p = event.getPlayer();
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s != null) {
            s.countBroken();
            s.rememberBroken(event.getBlock().getBlockData(), event.getBlock().getLocation());
            him.director().react(p, Trigger.MINE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        him.fakes().forget(b);
        him.memory().world().recordBuild(b.getWorld().getName(), b.getX(), b.getZ());
        him.memory().markWorldDirty();
        Player p = event.getPlayer();
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s != null) {
            s.countPlaced();
            signal(p, b.getLocation(), HeatMap.Signal.BUILD);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Location at = event.getInventory().getLocation();
        if (at != null && event.getPlayer() instanceof Player p) {
            signal(p, at, HeatMap.Signal.CONTAINER);
        }
    }

    /** Touching a fake block makes the client and server agree again immediately. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block b = event.getClickedBlock();
        if (b != null && him.fakes().isFake(event.getPlayer(), b)) {
            him.fakes().revertIfFake(event.getPlayer(), b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        long now = him.clock().now();
        if (event.getDamager() instanceof Player p && !him.entities().isOurs(event.getEntity())) {
            mark(p, now);
        }
        if (event.getEntity() instanceof Player p && !him.entities().isOurs(event.getDamager())) {
            mark(p, now);
        }
    }

    private void mark(Player p, long now) {
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s != null) {
            s.markCombat(now);
        }
    }

    private void signal(Player p, Location at, HeatMap.Signal signal) {
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s == null || at.getWorld() == null) {
            return;
        }
        PlayerMemory m = s.memory();
        m.heat().recordSignal(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), signal, him.clock().now(),
                him.config().memory().heatHalfLifeMs(), him.config().memory().heatCapacity());
        him.memory().markDirty(p.getUniqueId());
    }
}
