package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkDelta;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChangeStatsFactory {

    private ChangeStatsFactory() {
    }

    public static ChangeStats summarize(List<StoredBlockChange> changes) {
        Map<String, Boolean> blockTypes = new LinkedHashMap<>();
        Map<String, Integer> chunkCounters = new LinkedHashMap<>();

        for (StoredBlockChange change : changes) {
            chunkCounters.merge(chunkKey(change), 1, Integer::sum);
            blockTypes.put(change.newValue().blockId(), Boolean.TRUE);
        }

        return new ChangeStats(changes.size(), chunkCounters.size(), blockTypes.size());
    }

    public static PatchMetadata createPatchMetadata(
            String patchId,
            String projectId,
            String versionId,
            String fileName,
            List<PatchChunkSlice> slices
    ) {
        List<ChunkDelta> chunkDeltas = new ArrayList<>();
        for (PatchChunkSlice slice : slices) {
            chunkDeltas.add(new ChunkDelta(slice.chunkX(), slice.chunkZ(), slice.changeCount()));
        }

        return new PatchMetadata(
                patchId,
                projectId,
                versionId,
                fileName,
                List.copyOf(slices),
                new PatchStats(
                        chunkDeltas.stream().mapToInt(ChunkDelta::changedBlocks).sum(),
                        chunkDeltas.size()
                )
        );
    }

    public static List<ChunkDelta> chunkDeltas(List<StoredBlockChange> changes) {
        Map<String, Integer> chunkCounters = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            chunkCounters.merge(chunkKey(change), 1, Integer::sum);
        }

        List<ChunkDelta> chunkDeltas = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : chunkCounters.entrySet()) {
            String[] split = entry.getKey().split(":");
            chunkDeltas.add(new ChunkDelta(Integer.parseInt(split[0]), Integer.parseInt(split[1]), entry.getValue()));
        }

        return chunkDeltas;
    }

    public static PendingChangeSummary summarizePending(List<StoredBlockChange> changes) {
        int added = 0;
        int removed = 0;
        int changed = 0;

        for (StoredBlockChange change : changes) {
            boolean oldAir = isAir(change.oldValue().blockId());
            boolean newAir = isAir(change.newValue().blockId());
            if (oldAir && !newAir) {
                added += 1;
            } else if (!oldAir && newAir) {
                removed += 1;
            } else {
                changed += 1;
            }
        }

        return new PendingChangeSummary(added, removed, changed);
    }

    private static String chunkKey(StoredBlockChange change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }

    private static boolean isAir(String state) {
        return state == null
                || state.isBlank()
                || state.contains("minecraft:air");
    }
}
