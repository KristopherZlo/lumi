package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.StreamSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reads currently loaded decoded state from one server dimension. */
public final class MinecraftWorldStateReader implements WorldStateReader {
    private final ServerLevel level;
    private final MinecraftSectionCapture sections = new MinecraftSectionCapture();
    private final MinecraftEntityChunkCapture entities = new MinecraftEntityChunkCapture();

    public MinecraftWorldStateReader(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
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
        var matching = StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(entity -> entity.chunkPosition().x == key.chunkX()
                        && entity.chunkPosition().z == key.chunkZ());
        return entities.capture(level, matching);
    }
}
