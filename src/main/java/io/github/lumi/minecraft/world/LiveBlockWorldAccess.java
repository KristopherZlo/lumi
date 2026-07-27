package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Narrow server-thread port used by verified session Undo/Redo. */
public interface LiveBlockWorldAccess {
    void requirePrepared(BlockSnapshot state) throws IOException;

    BlockSnapshot read(BlockPosition position) throws IOException;

    void write(BlockPosition position, BlockSnapshot state) throws IOException;

    default CompletableFuture<Void> finishLighting(Set<BlockPosition> positions) {
        return CompletableFuture.completedFuture(null);
    }
}
