package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

final class CaptureSessionDiagnostics {

    private int acceptedMutations;
    private final Map<String, ChunkPoint> activeChunks = new LinkedHashMap<>();
    private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
    private final Map<String, Integer> transitionCounts = new LinkedHashMap<>();
    private BlockPos lastPos;
    private ChunkPoint lastChunk = new ChunkPoint(0, 0);
    private String lastSource = "unknown";
    private String lastOldBlockId = "minecraft:air";
    private String lastNewBlockId = "minecraft:air";
    private boolean lastOldBlockEntity;
    private boolean lastNewBlockEntity;

    void record(
            WorldMutationSource source,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            boolean oldBlockEntity,
            boolean newBlockEntity
    ) {
        this.acceptedMutations += 1;
        String sourceKey = source == null ? "unknown" : source.name();
        String transitionKey = blockId(oldState) + " -> " + blockId(newState);
        this.sourceCounts.merge(sourceKey, 1, Integer::sum);
        this.transitionCounts.merge(transitionKey, 1, Integer::sum);
        this.lastPos = pos == null ? null : pos.immutable();
        this.lastChunk = pos == null ? new ChunkPoint(0, 0) : new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
        this.addActiveChunk(this.lastChunk);
        this.lastSource = sourceKey;
        this.lastOldBlockId = blockId(oldState);
        this.lastNewBlockId = blockId(newState);
        this.lastOldBlockEntity = oldBlockEntity;
        this.lastNewBlockEntity = newBlockEntity;
    }

    void seedFromBuffer(TrackedChangeBuffer buffer) {
        if (!this.activeChunks.isEmpty() || buffer == null || buffer.isEmpty()) {
            return;
        }
        for (var change : buffer.orderedChanges()) {
            this.addActiveChunk(ChunkPoint.from(change.pos()));
        }
        for (var change : buffer.orderedEntityChanges()) {
            this.addActiveChunk(change.chunk());
        }
    }

    boolean isWithinActiveRegion(ChunkPoint chunk, int radius) {
        if (chunk == null || this.activeChunks.isEmpty()) {
            return false;
        }
        for (int chunkX = chunk.x() - radius; chunkX <= chunk.x() + radius; chunkX++) {
            for (int chunkZ = chunk.z() - radius; chunkZ <= chunk.z() + radius; chunkZ++) {
                if (this.activeChunks.containsKey(key(new ChunkPoint(chunkX, chunkZ)))) {
                    return true;
                }
            }
        }
        return false;
    }

    void addActiveChunk(ChunkPoint chunk) {
        this.activeChunks.putIfAbsent(key(chunk), chunk);
    }

    int acceptedMutations() {
        return this.acceptedMutations;
    }

    String lastSource() {
        return this.lastSource;
    }

    BlockPos lastPos() {
        return this.lastPos;
    }

    ChunkPoint lastChunk() {
        return this.lastChunk;
    }

    String lastOldBlockId() {
        return this.lastOldBlockId;
    }

    String lastNewBlockId() {
        return this.lastNewBlockId;
    }

    boolean lastOldBlockEntity() {
        return this.lastOldBlockEntity;
    }

    boolean lastNewBlockEntity() {
        return this.lastNewBlockEntity;
    }

    String describeTopSources(int limit) {
        return this.describeTopCounts(this.sourceCounts, limit);
    }

    String describeTopTransitions(int limit) {
        return this.describeTopCounts(this.transitionCounts, limit);
    }

    private String describeTopCounts(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String blockId(BlockState state) {
        if (state == null) {
            return "minecraft:air";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String key(ChunkPoint chunk) {
        return chunk.x() + ":" + chunk.z();
    }
}
