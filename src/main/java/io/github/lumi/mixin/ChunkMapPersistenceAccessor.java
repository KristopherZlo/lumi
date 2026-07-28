package io.github.lumi.mixin;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapPersistenceAccessor {
    @Accessor("activeChunkWrites")
    AtomicInteger lumi$activeChunkWrites();

    @Invoker("save")
    boolean lumi$save(ChunkAccess chunk);
}
