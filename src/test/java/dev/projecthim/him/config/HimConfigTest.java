package dev.projecthim.him.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.event.impl.EventCatalog;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HimConfigTest {

    private static final Set<String> EVENTS = EventCatalog.create().stream().map(HorrorEvent::id)
            .collect(Collectors.toSet());

    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(HimConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
    }

    @Test
    void shippedConfigIsValidWithoutWarnings() throws ConfigException {
        HimConfig c = HimConfig.load(defaults(), EVENTS);
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertTrue(c.enabled());
        assertFalse(c.apparitions().skinValue().isBlank());
        assertFalse(c.apparitions().skinSignature().isBlank());
        assertTrue(c.chase().settings().runSpeed() < 5.6, "default hunt must be outrunnable");
        assertEquals(8, c.messages().signTexts().size());
    }

    @Test
    void reportsEveryErrorAtOnce() {
        YamlConfiguration y = defaults();
        y.set("director.frequency", "often");
        y.set("chase.speed.run", 1.0);
        y.set("director.mean-gap-minutes", java.util.List.of(1, 2));
        y.set("worlds.mode", "sometimes");
        y.set("performance.mspt-critical", 10);
        ConfigException e = assertThrows(ConfigException.class, () -> HimConfig.load(y, EVENTS));
        assertTrue(e.errors().size() >= 5, "errors: " + e.errors());
        assertTrue(e.errors().stream().anyMatch(s -> s.startsWith("director.frequency")));
        assertTrue(e.errors().stream().anyMatch(s -> s.startsWith("worlds.mode")));
    }

    @Test
    void unknownDisabledEventIsAWarningNotAnError() throws ConfigException {
        YamlConfiguration y = defaults();
        y.set("director.disabled-events", java.util.List.of("hunt", "does_not_exist"));
        HimConfig c = HimConfig.load(y, EVENTS);
        assertTrue(c.director().disabledEvents().contains("hunt"));
        assertEquals(1, c.warnings().size());
    }

    @Test
    void disablingChaseRemovesHuntAggression() throws ConfigException {
        YamlConfiguration y = defaults();
        y.set("chase.enabled", false);
        assertEquals(0.0, HimConfig.load(y, EVENTS).director().aggression());
    }

    @Test
    void worldListModes() throws ConfigException {
        YamlConfiguration y = defaults();
        y.set("worlds.mode", "whitelist");
        y.set("worlds.list", java.util.List.of("Survival"));
        HimConfig c = HimConfig.load(y, EVENTS);
        assertTrue(c.worlds().allowed("survival"));
        assertFalse(c.worlds().allowed("creative"));
    }

    @Test
    void combatAndEliteDefaults() throws ConfigException {
        HimConfig c = HimConfig.load(defaults(), EVENTS);
        assertEquals(60, c.chase().combat().health());
        assertEquals(3, c.chase().elite().minionCount());
        assertEquals(java.util.List.of("ZOMBIE", "SKELETON"), c.chase().elite().minionTypes());
        assertEquals(java.util.List.of("SLOWNESS", "WEAKNESS", "DARKNESS"), c.chase().weapons().potionEffects());
        assertEquals(4, c.chase().elite().bossBarMinTier());
    }

    @Test
    void listsMissingFromAnOlderConfigFallBackToTheBundledDefaults() throws ConfigException {
        // Like JavaPlugin#getConfig(): the server's file, with the bundled config.yml as defaults.
        YamlConfiguration old = new YamlConfiguration();
        old.set("config-version", HimConfig.CURRENT_VERSION);
        old.setDefaults(defaults());
        HimConfig c;
        try {
            c = HimConfig.load(old, EVENTS);
        } catch (ConfigException e) {
            throw new AssertionError(e.errors().toString(), e);
        }
        assertEquals(java.util.List.of("ZOMBIE", "SKELETON"), c.chase().elite().minionTypes());
        assertEquals(java.util.List.of("SLOWNESS", "WEAKNESS", "DARKNESS"), c.chase().weapons().potionEffects());
        assertFalse(c.messages().signTexts().isEmpty());
    }
}
