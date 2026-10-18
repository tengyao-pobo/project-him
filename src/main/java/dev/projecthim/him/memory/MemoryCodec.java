package dev.projecthim.him.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/** JSON encoding of memory objects. Thread safe. */
public final class MemoryCodec {

    private static final Gson GSON = new GsonBuilder()
            .serializeSpecialFloatingPointValues()
            .disableHtmlEscaping()
            .create();

    private MemoryCodec() {
    }

    public static String encode(PlayerMemory memory) {
        return GSON.toJson(memory);
    }

    public static String encode(WorldState state) {
        return GSON.toJson(state);
    }

    public static PlayerMemory decodePlayer(String json) {
        PlayerMemory m = GSON.fromJson(json, PlayerMemory.class);
        if (m == null) {
            throw new JsonParseException("empty player memory document");
        }
        return m;
    }

    public static WorldState decodeWorld(String json) {
        WorldState s = GSON.fromJson(json, WorldState.class);
        if (s == null) {
            throw new JsonParseException("empty world state document");
        }
        return s;
    }
}
