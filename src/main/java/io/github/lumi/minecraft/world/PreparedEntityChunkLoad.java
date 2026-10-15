package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import java.util.Objects;
import net.minecraft.world.level.entity.ChunkEntities;

/** Carries a worker-captured baseline until vanilla accepts this entity load. */
public final class PreparedEntityChunkLoad<T> extends ChunkEntities<T> {
    private final EntityChunkBlob baseline;

    public PreparedEntityChunkLoad(ChunkEntities<T> source, EntityChunkBlob baseline) {
        super(Objects.requireNonNull(source, "source").getPos(), source.getEntities().toList());
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    public EntityChunkBlob baseline() {
        return baseline;
    }
}
