package dev.projecthim.him.memory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for HIM's memory. Implementations are blocking and must only be called from
 * the IO executor, never from the server thread.
 */
public interface MemoryRepository {

    Optional<PlayerMemory> loadPlayer(UUID id) throws IOException;

    /** Writes an already serialised snapshot. Serialisation happens on the owning thread. */
    void savePlayer(UUID id, String json) throws IOException;

    void deletePlayer(UUID id) throws IOException;

    List<UUID> listPlayers() throws IOException;

    /** Last time the player's file was written, or 0 if unknown. */
    long lastModified(UUID id) throws IOException;

    Optional<WorldState> loadWorld() throws IOException;

    void saveWorld(String json) throws IOException;
}
