package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reads currently loaded decoded state from one server dimension. */
public final class MinecraftWorldStateReader implements WorldStateReader {
    private final ServerLevel level;
    private final MinecraftSectionCapture sections = new MinecraftSectionCapture();
    private final MinecraftEntityChunkCapture entities = new MinecraftEntityChunkCapture();
    private final ChunkEntityLookup entityLookup;

    public MinecraftWorldStateReader(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        entityLookup = ChunkEntityLookup.forLevel(level);
    }

    @Override
    public SectionBlob read(SectionKey key) throws IOException {
        LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (chunk == null) {
            throw new IOException("Dirty Lumi section is not loaded: " + key);
        }
        return sections.capture(level, chunk, key.sectionY());
    }

    @Override
    public EntityChunkBlob read(EntityChunkKey key) throws IOException {
        return entities.capture(level, entityLookup.inChunk(key));
    }

    @Override
    public Map<UUID, PlayerSpawn> readPlayerSpawns() {
        Map<UUID, PlayerSpawn> spawns = new HashMap<>();
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            var config = player.getRespawnConfig();
            if (config == null || !config.respawnData().dimension().equals(level.dimension())) {
                continue;
            }
            var data = config.respawnData();
            var position = data.pos();
            spawns.put(player.getUUID(), new PlayerSpawn(
                    position.getX(), position.getY(), position.getZ(),
                    data.yaw(), data.pitch(), config.forced()));
        }
        return Map.copyOf(spawns);
    }
}
