package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import java.io.IOException;

/** Narrow server-thread port used by verified session Undo/Redo. */
public interface LiveBlockWorldAccess {
    BlockSnapshot read(BlockPosition position) throws IOException;

    void write(BlockPosition position, BlockSnapshot state) throws IOException;
}
