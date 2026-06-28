package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class LiveEntityChunkCollector {

    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();

    public List<ChunkPoint> collect(ServerLevel level) throws IOException {
        if (level == null) {
            return List.of();
        }
        return this.serverThreadExecutor.call(level.getServer(), () -> {
            LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity != null && !(entity instanceof ServerPlayer) && !entity.isRemoved()) {
                    chunks.add(ChunkPoint.from(entity.blockPosition()));
                }
            }
            return List.copyOf(chunks);
        });
    }
}
