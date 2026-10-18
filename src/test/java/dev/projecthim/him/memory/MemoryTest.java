package dev.projecthim.him.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryTest {

    private static final long DAY = 86_400_000L;
    private static final long HALF_LIFE = 10 * DAY;

    @Test
    void baseEstimateFollowsSleepAndStorageNotJustTraffic() {
        HeatMap h = new HeatMap();
        long now = 0;
        // Lots of walking through a corridor far away...
        for (int i = 0; i < 300; i++) {
            h.recordDwell("w", 500 + i % 16, 64, 500, 5, now, HALF_LIFE, 96);
        }
        // ...but sleeping and opening chests here.
        for (int i = 0; i < 5; i++) {
            h.recordSignal("w", 100, 70, 100, HeatMap.Signal.SLEEP, now, HALF_LIFE, 96);
            h.recordSignal("w", 102, 70, 101, HeatMap.Signal.CONTAINER, now, HALF_LIFE, 96);
        }
        HeatMap.BaseEstimate base = h.estimateBase("w", now, HALF_LIFE).orElseThrow();
        assertEquals(100, base.x(), 4);
        assertEquals(100, base.z(), 4);
        assertTrue(base.confidence() > 0.3);
        assertTrue(h.estimateBase("other", now, HALF_LIFE).isEmpty());
    }

    @Test
    void heatDecaysAndStaysBounded() {
        HeatMap h = new HeatMap();
        for (int i = 0; i < 500; i++) {
            h.recordDwell("w", i * 16, 64, 0, 10, 0, HALF_LIFE, 32);
        }
        assertTrue(h.size() <= 32);
        h.recordDwell("w", 0, 64, 0, 600, 0, HALF_LIFE, 32);
        double fresh = h.hottest("w", 1, 0, HALF_LIFE).getFirst().dwellSeconds();
        double later = h.hottest("w", 1, 30 * DAY, HALF_LIFE).getFirst().dwellSeconds();
        assertTrue(later < fresh * 0.2, "old activity must fade");
    }

    @Test
    void encountersAreBounded() {
        PlayerMemory m = new PlayerMemory(UUID.randomUUID(), "p", 0);
        for (int i = 0; i < 500; i++) {
            m.recordEncounter(new EncounterRecord(i, "e" + (i % 5), "PRIVATE", "w", i, 64, 0, Outcome.NOTICED));
        }
        assertTrue(m.encounters().size() <= 48);
        assertEquals(499, m.recentEncounters(1).getFirst().time());
    }

    @Test
    void codecRoundTripKeepsEverythingImportant() {
        SplittableRandom random = new SplittableRandom(5);
        UUID id = UUID.randomUUID();
        PlayerMemory m = new PlayerMemory(id, "Steve", 1000);
        m.addPlayTime(3600, 2000);
        m.heat().recordSignal("world", 10, 64, 10, HeatMap.Signal.SLEEP, 0, HALF_LIFE, 96);
        m.recordEncounter(new EncounterRecord(5, "hunt", "PRIVATE", "world", 1, 2, 3, Outcome.ESCAPED));
        m.chase().recordEscape(ChaseStats.EscapeMethod.WATER);
        m.chase().recordEscape(ChaseStats.EscapeMethod.WATER);
        m.behavior().recordReaction(Outcome.FLED);
        m.expectations().recordEvent("hunt", "HUNT");
        m.setPendingReturn(new Landmark(Landmark.Kind.LOGOUT_SITE, id, "world", 1, 2, 3, 4));
        m.setStageOverride(3);

        PlayerMemory copy = MemoryCodec.decodePlayer(MemoryCodec.encode(m));
        copy.sanitize();
        assertEquals(id, copy.id());
        assertEquals(3600, copy.playSeconds());
        assertEquals(3, copy.stageOverride());
        assertEquals(1, copy.encounters().size());
        assertEquals(ChaseStats.EscapeMethod.WATER, copy.chase().favouriteEscape());
        assertEquals(Landmark.Kind.LOGOUT_SITE, copy.pendingReturn().kind());
        assertTrue(copy.heat().estimateBase("world", 0, HALF_LIFE).isPresent());
        assertEquals(1, copy.expectations().count("hunt"));

        WorldState w = WorldState.fresh(random, 0);
        w.addLandmark(new Landmark(Landmark.Kind.DEATH_SITE, id, "world", 0, 0, 0, 0));
        w.recordBuild("world", 5, 5);
        WorldState wc = MemoryCodec.decodeWorld(MemoryCodec.encode(w));
        wc.sanitize(random, 0);
        assertEquals(w.personality().snapshot(), wc.personality().snapshot());
        assertEquals(1, wc.landmarksOf(id).size());
        assertEquals(1, wc.buildDensity("world", 5, 5));
    }

    @Test
    void sanitizeRepairsBrokenDocuments() {
        PlayerMemory m = MemoryCodec.decodePlayer("{\"id\":\"" + UUID.randomUUID() + "\",\"obsession\":7,"
                + "\"stageOverride\":99,\"heat\":null,\"encounters\":null}");
        m.sanitize();
        assertEquals(1.0, m.obsession());
        assertEquals(-1, m.stageOverride());
        assertEquals(0, m.encounters().size());
        assertEquals(0, m.heat().size());
    }

    @Test
    void repositoryWritesAtomicallyAndQuarantinesCorruptFiles(@TempDir Path dir) throws IOException {
        JsonMemoryRepository repo = new JsonMemoryRepository(dir, Logger.getAnonymousLogger());
        UUID id = UUID.randomUUID();
        PlayerMemory m = new PlayerMemory(id, "Alex", 0);
        repo.savePlayer(id, MemoryCodec.encode(m));
        assertEquals("Alex", repo.loadPlayer(id).orElseThrow().lastName());
        assertTrue(repo.listPlayers().contains(id));
        assertFalse(Files.exists(dir.resolve("players").resolve(id + ".json.tmp")));

        Files.writeString(dir.resolve("players").resolve(id + ".json"), "{ this is not json");
        assertTrue(repo.loadPlayer(id).isEmpty());
        try (var files = Files.list(dir.resolve("players"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }

        // A document whose id does not match its file name is rejected too.
        UUID other = UUID.randomUUID();
        repo.savePlayer(other, MemoryCodec.encode(new PlayerMemory(UUID.randomUUID(), "x", 0)));
        assertTrue(repo.loadPlayer(other).isEmpty());

        assertTrue(repo.loadWorld().isEmpty());
        repo.saveWorld(MemoryCodec.encode(WorldState.fresh(new SplittableRandom(1), 0)));
        assertTrue(repo.loadWorld().isPresent());
    }
}
