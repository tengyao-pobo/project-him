package dev.projecthim.him;

import dev.projecthim.him.command.HimCommand;
import dev.projecthim.him.config.ConfigException;
import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.event.impl.EventCatalog;
import dev.projecthim.him.listener.EntityListener;
import dev.projecthim.him.listener.PlayerListener;
import dev.projecthim.him.util.HimLog;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Loads and validates the configuration, starts {@link Him}, wires listeners
 * and commands. If the configuration is invalid on first start the plugin stays loaded but dormant,
 * reports every error, and can be started with {@code /him reload} once the file is fixed.
 */
public final class HimPlugin extends JavaPlugin {

    private HimLog log;
    private Him him;
    private Set<String> eventIds;

    @Override
    public void onEnable() {
        log = new HimLog(getLogger());
        saveDefaultConfig();
        eventIds = EventCatalog.create().stream().map(HorrorEvent::id).collect(Collectors.toUnmodifiableSet());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("him", "HIM — He Is Memory 管理指令", List.of(), new HimCommand(this)));
        HimConfig config = loadConfig(getServer().getConsoleSender());
        if (config == null) {
            log.warn("HIM is dormant because of configuration errors. Fix config.yml and run /him reload.");
            return;
        }
        startHim(config);
    }

    @Override
    public void onDisable() {
        stopHim();
    }

    private void startHim(HimConfig config) {
        try {
            him = new Him(this, log, config);
        } catch (IOException e) {
            log.error("Could not open HIM's data folder; HIM stays dormant", e);
            him = null;
            return;
        }
        him.start();
        getServer().getPluginManager().registerEvents(new PlayerListener(him), this);
        getServer().getPluginManager().registerEvents(new EntityListener(him), this);
        log.info("HIM is watching. " + him.events().all().size() + " events loaded.");
    }

    private void stopHim() {
        if (him != null) {
            HandlerList.unregisterAll(this);
            try {
                him.stop();
            } catch (RuntimeException e) {
                log.error("Error while stopping HIM", e);
            }
            him = null;
        }
    }

    /** Parses config.yml, reporting every problem to the sender. Returns null if invalid. */
    private HimConfig loadConfig(CommandSender reportTo) {
        reloadConfig();
        try {
            HimConfig config = HimConfig.load(getConfig(), eventIds);
            for (String w : config.warnings()) {
                log.warn("config.yml: " + w);
                if (reportTo != getServer().getConsoleSender()) {
                    reportTo.sendMessage(Component.text("[HIM] 警告 " + w, NamedTextColor.YELLOW));
                }
            }
            return config;
        } catch (ConfigException e) {
            log.warn("config.yml has " + e.errors().size() + " error(s):");
            for (String err : e.errors()) {
                log.warn("  - " + err);
            }
            if (reportTo != getServer().getConsoleSender()) {
                reportTo.sendMessage(Component.text("[HIM] 設定檔有 " + e.errors().size() + " 個錯誤：", NamedTextColor.RED));
                for (String err : e.errors()) {
                    reportTo.sendMessage(Component.text("  - " + err, NamedTextColor.RED));
                }
            }
            return null;
        }
    }

    /** Re-reads config.yml. On error the previous configuration stays active. */
    public void reloadHim(CommandSender sender) {
        HimConfig config = loadConfig(sender);
        if (config == null) {
            sender.sendMessage(Component.text("[HIM] 重新載入失敗，繼續使用原本的設定。", NamedTextColor.RED));
            return;
        }
        if (him == null) {
            startHim(config);
        } else {
            him.applyConfig(config);
        }
        sender.sendMessage(Component.text("[HIM] 設定已重新載入。" + (config.warnings().isEmpty() ? ""
                : "（" + config.warnings().size() + " 個警告，詳見主控台）"), NamedTextColor.GREEN));
    }

    public Him him() {
        return him;
    }
}
