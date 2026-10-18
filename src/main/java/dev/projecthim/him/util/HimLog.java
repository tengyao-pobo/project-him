package dev.projecthim.him.util;

import dev.projecthim.him.config.HimConfig.LogLevel;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Leveled logging with lazy messages, plus live debug forwarding to administrators who enabled it
 * with {@code /him debug on}.
 */
public final class HimLog {

    private final Logger logger;
    private volatile LogLevel level = LogLevel.INFO;
    private final Set<UUID> watchers = ConcurrentHashMap.newKeySet();

    public HimLog(Logger logger) {
        this.logger = logger;
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }

    public LogLevel level() {
        return level;
    }

    public Logger raw() {
        return logger;
    }

    public void info(String message) {
        if (level.allows(LogLevel.INFO)) {
            logger.info(message);
        }
    }

    public void warn(String message) {
        logger.warning(message);
    }

    public void error(String message, Throwable t) {
        logger.log(Level.SEVERE, message, t);
    }

    public boolean debugEnabled() {
        return level.allows(LogLevel.DEBUG) || !watchers.isEmpty();
    }

    public void debug(Supplier<String> message) {
        boolean console = level.allows(LogLevel.DEBUG);
        if (!console && watchers.isEmpty()) {
            return;
        }
        String text = message.get();
        if (console) {
            logger.info("[debug] " + text);
        }
        forward(text);
    }

    public void trace(Supplier<String> message) {
        if (level.allows(LogLevel.TRACE)) {
            logger.info("[trace] " + message.get());
        }
    }

    private void forward(String text) {
        if (watchers.isEmpty() || !Bukkit.isPrimaryThread()) {
            return;
        }
        Component line = Component.text("[HIM] ", NamedTextColor.DARK_RED)
                .append(Component.text(text, NamedTextColor.GRAY));
        for (UUID id : watchers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(line);
            }
        }
    }

    public boolean toggleWatcher(UUID id, boolean on) {
        return on ? watchers.add(id) : watchers.remove(id);
    }

    public boolean isWatching(UUID id) {
        return watchers.contains(id);
    }
}
