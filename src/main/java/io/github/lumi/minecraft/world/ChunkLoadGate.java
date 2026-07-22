package io.github.lumi.minecraft.world;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Prevents a stored chunk from becoming live while its vanilla I/O is rewritten. */
public final class ChunkLoadGate {
    private static final Map<ServerLevel, Set<Long>> GATED = new IdentityHashMap<>();

    private ChunkLoadGate() { }

    public static synchronized Lease tryAcquire(ServerLevel level, ChunkPos position) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        long key = position.toLong();
        Set<Long> dimension = GATED.computeIfAbsent(level, ignored -> new HashSet<>());
        if (!dimension.add(key)) {
            return null;
        }
        if (level.getChunkSource().getChunkNow(position.x, position.z) != null
                || level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key) != null) {
            release(level, key);
            return null;
        }
        return new Lease(level, key);
    }

    public static synchronized boolean isGated(ServerLevel level, ChunkPos position) {
        Set<Long> dimension = GATED.get(Objects.requireNonNull(level, "level"));
        return dimension != null && dimension.contains(
                Objects.requireNonNull(position, "position").toLong());
    }

    private static synchronized void release(ServerLevel level, long key) {
        Set<Long> dimension = GATED.get(level);
        if (dimension == null) {
            return;
        }
        dimension.remove(key);
        if (dimension.isEmpty()) {
            GATED.remove(level);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ServerLevel level;
        private final long key;
        private boolean closed;

        private Lease(ServerLevel level, long key) {
            this.level = level;
            this.key = key;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(level, key);
            }
        }
    }
}
