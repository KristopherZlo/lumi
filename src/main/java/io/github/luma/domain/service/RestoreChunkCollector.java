package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.domain.model.SectionChangeMask;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class RestoreChunkCollector {

    private final PatchMetaRepository patchMetaRepository;

    RestoreChunkCollector(PatchMetaRepository patchMetaRepository) {
        this.patchMetaRepository = patchMetaRepository;
    }

    List<ChunkPoint> touchedChunksForVersions(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (ProjectVersion version : versions == null ? List.<ProjectVersion>of() : versions) {
            for (String patchId : version.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                this.addPatchMetadataChunks(chunks, metadata);
            }
        }
        return List.copyOf(chunks);
    }

    List<ChunkPoint> touchedChunksForDraft(RecoveryDraft draft) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        if (draft == null) {
            return List.of();
        }
        for (StoredBlockChange change : draft.changes()) {
            chunks.add(ChunkPoint.from(change.pos()));
        }
        for (StoredEntityChange change : draft.entityChanges()) {
            if (change.chunk() != null) {
                chunks.add(change.chunk());
            }
        }
        return List.copyOf(chunks);
    }

    List<ChunkPoint> batchChunks(List<PreparedChunkBatch> batches) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch != null && batch.chunk() != null) {
                chunks.add(batch.chunk());
            }
        }
        return List.copyOf(chunks);
    }

    List<ChunkPoint> chunksForPositions(List<BlockPoint> positions) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (BlockPoint position : positions == null ? List.<BlockPoint>of() : positions) {
            if (position == null) {
                continue;
            }
            chunks.add(ChunkPoint.from(position));
        }
        return List.copyOf(chunks);
    }

    Map<ChunkPoint, List<BlockPoint>> positionsByChunk(List<BlockPoint> positions) {
        Map<ChunkPoint, List<BlockPoint>> grouped = new LinkedHashMap<>();
        for (BlockPoint position : positions == null ? List.<BlockPoint>of() : positions) {
            if (position == null) {
                continue;
            }
            grouped.computeIfAbsent(ChunkPoint.from(position), ignored -> new ArrayList<>())
                    .add(position);
        }
        return grouped;
    }

    List<BlockPoint> blockPositions(List<PreparedChunkBatch> batches) {
        Map<Long, BlockPoint> positions = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch == null) {
                continue;
            }
            for (var placement : batch.placements()) {
                if (placement != null && placement.pos() != null) {
                    BlockPoint point = BlockPoint.from(placement.pos());
                    positions.putIfAbsent(placement.pos().asLong(), point);
                }
            }
            for (var section : batch.nativeSections()) {
                if (section == null || section.chunk() == null || section.buffer() == null) {
                    continue;
                }
                section.buffer().changedCells().forEachSetCell(localIndex -> {
                    BlockPoint point = new BlockPoint(
                            (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                            (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                            (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
                    );
                    positions.putIfAbsent(BlockPos.asLong(point.x(), point.y(), point.z()), point);
                });
            }
        }
        return List.copyOf(positions.values());
    }

    List<ChunkPoint> touchedChunksForPlan(
            List<ChunkPoint> baselineGaps,
            List<PatchMetadata> patchChain
    ) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (ChunkPoint chunk : baselineGaps == null ? List.<ChunkPoint>of() : baselineGaps) {
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        for (PatchMetadata metadata : patchChain == null ? List.<PatchMetadata>of() : patchChain) {
            this.addPatchMetadataChunks(chunks, metadata);
        }
        return List.copyOf(chunks);
    }

    @SafeVarargs
    final List<ChunkPoint> mergeChunks(List<ChunkPoint>... chunkLists) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (List<ChunkPoint> chunkList : chunkLists) {
            if (chunkList == null) {
                continue;
            }
            for (ChunkPoint chunk : chunkList) {
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        return List.copyOf(chunks);
    }

    List<ChunkPoint> chunksIntersecting(Bounds3i bounds) {
        if (bounds == null) {
            return List.of();
        }

        List<ChunkPoint> chunks = new ArrayList<>();
        int minChunkX = Math.floorDiv(bounds.min().x(), 16);
        int maxChunkX = Math.floorDiv(bounds.max().x(), 16);
        int minChunkZ = Math.floorDiv(bounds.min().z(), 16);
        int maxChunkZ = Math.floorDiv(bounds.max().z(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPoint(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private void addPatchMetadataChunks(Set<ChunkPoint> chunks, PatchMetadata metadata) {
        if (metadata == null) {
            return;
        }
        for (var chunk : metadata.chunks()) {
            chunks.add(new ChunkPoint(chunk.chunkX(), chunk.chunkZ()));
        }
    }
}
