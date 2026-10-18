package dev.projecthim.him.memory;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON document per player plus one for the world state. Writes go to a temporary file which
 * is atomically moved into place, so a crash mid-write never leaves a half written document.
 * Unreadable documents are quarantined (renamed) instead of being silently overwritten.
 */
public final class JsonMemoryRepository implements MemoryRepository {

    private final Path playersDir;
    private final Path worldFile;
    private final Logger logger;

    public JsonMemoryRepository(Path dataDir, Logger logger) throws IOException {
        this.playersDir = dataDir.resolve("players");
        this.worldFile = dataDir.resolve("world-state.json");
        this.logger = logger;
        Files.createDirectories(playersDir);
    }

    private Path playerFile(UUID id) {
        return playersDir.resolve(id + ".json");
    }

    @Override
    public Optional<PlayerMemory> loadPlayer(UUID id) throws IOException {
        Path file = playerFile(id);
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            PlayerMemory memory = MemoryCodec.decodePlayer(json);
            memory.sanitize();
            if (memory.id() == null || !memory.id().equals(id)) {
                throw new JsonParseException("document id does not match file name");
            }
            return Optional.of(memory);
        } catch (RuntimeException e) {
            quarantine(file, e);
            return Optional.empty();
        }
    }

    @Override
    public void savePlayer(UUID id, String json) throws IOException {
        writeAtomically(playerFile(id), json);
    }

    @Override
    public void deletePlayer(UUID id) throws IOException {
        Files.deleteIfExists(playerFile(id));
    }

    @Override
    public List<UUID> listPlayers() throws IOException {
        List<UUID> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playersDir, "*.json")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                try {
                    ids.add(UUID.fromString(name.substring(0, name.length() - 5)));
                } catch (IllegalArgumentException ignored) {
                    // Not one of ours.
                }
            }
        }
        return ids;
    }

    @Override
    public long lastModified(UUID id) throws IOException {
        Path file = playerFile(id);
        return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
    }

    @Override
    public Optional<WorldState> loadWorld() throws IOException {
        String json;
        try {
            json = Files.readString(worldFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(MemoryCodec.decodeWorld(json));
        } catch (RuntimeException e) {
            quarantine(worldFile, e);
            return Optional.empty();
        }
    }

    @Override
    public void saveWorld(String json) throws IOException {
        writeAtomically(worldFile, json);
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine(Path file, Exception cause) throws IOException {
        Path broken = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
        logger.log(Level.WARNING, "Memory file " + file.getFileName() + " could not be read and was moved to "
                + broken.getFileName() + ". Starting with a fresh memory for it. Cause: " + cause.getMessage());
    }
}
