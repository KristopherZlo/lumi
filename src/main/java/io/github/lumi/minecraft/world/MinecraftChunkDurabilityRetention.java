package io.github.lumi.minecraft.world;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Server-thread ticket retention for chunks waiting on Lumi durability. */
public final class MinecraftChunkDurabilityRetention implements ChunkDurabilityRetention {
    private static final int RADIUS = 0;
    private static final TicketType DURABILITY_TICKET = new TicketType(
            Long.MAX_VALUE,
            TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final ServerLevel level;

    public MinecraftChunkDurabilityRetention(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void retain(int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException(
                    "Chunk durability must be retained before server-thread mutation");
        }
        level.getChunkSource().addTicketWithRadius(
                DURABILITY_TICKET, new ChunkPos(chunkX, chunkZ), RADIUS);
    }

    @Override
    public void release(int chunkX, int chunkZ) {
        level.getServer().execute(() -> level.getChunkSource().removeTicketWithRadius(
                DURABILITY_TICKET, new ChunkPos(chunkX, chunkZ), RADIUS));
    }
}
