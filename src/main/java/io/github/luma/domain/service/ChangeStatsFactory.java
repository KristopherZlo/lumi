package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.BlockPatch;
import io.github.luma.domain.model.ChunkDelta;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.PatchStats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChangeStatsFactory {

    private ChangeStatsFactory() {
    }

    public static ChangeStats summarize(List<BlockChangeRecord> changes) {
        Map<String, Boolean> blockTypes = new LinkedHashMap<>();
        Map<String, Integer> chunkCounters = new LinkedHashMap<>();

        for (BlockChangeRecord change : changes) {
            chunkCounters.merge(chunkKey(change), 1, Integer::sum);
            blockTypes.put(change.newState(), Boolean.TRUE);
        }

        return new ChangeStats(changes.size(), chunkCounters.size(), blockTypes.size());
    }

    public static BlockPatch createPatch(String patchId, String projectId, String versionId, String fileName, List<BlockChangeRecord> changes) {
        Map<String, Integer> chunkCounters = new LinkedHashMap<>();
        for (BlockChangeRecord change : changes) {
            chunkCounters.merge(chunkKey(change), 1, Integer::sum);
        }

        List<ChunkDelta> chunkDeltas = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : chunkCounters.entrySet()) {
            String[] split = entry.getKey().split(":");
            chunkDeltas.add(new ChunkDelta(Integer.parseInt(split[0]), Integer.parseInt(split[1]), entry.getValue()));
        }

        return new BlockPatch(
                patchId,
                projectId,
                versionId,
                fileName,
                chunkDeltas,
                new PatchStats(changes.size(), chunkDeltas.size())
        );
    }

    public static PendingChangeSummary summarizePending(List<BlockChangeRecord> changes) {
        int added = 0;
        int removed = 0;
        int changed = 0;

        for (BlockChangeRecord change : changes) {
            boolean oldAir = isAir(change.oldState());
            boolean newAir = isAir(change.newState());
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

    private static String chunkKey(BlockChangeRecord change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }

    private static boolean isAir(String state) {
        return state == null
                || state.isBlank()
                || state.contains("minecraft:air");
    }
}
