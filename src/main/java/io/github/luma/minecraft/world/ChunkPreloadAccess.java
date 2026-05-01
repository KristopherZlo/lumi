package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;

interface ChunkPreloadAccess {

    boolean isLoaded(ChunkPoint chunk);

    boolean load(ChunkPoint chunk);

    void acquireTicket(ChunkPoint chunk);

    void releaseTicket(ChunkPoint chunk);
}
