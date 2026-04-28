package io.github.luma.domain.service;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.VersionDiff;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MaterialDeltaService {

    public List<MaterialDeltaEntry> summarize(VersionDiff diff) {
        Map<String, int[]> counts = new HashMap<>();

        for (var block : diff.changedBlocks()) {
            String leftBlockId = block.leftBlockId();
            String rightBlockId = block.rightBlockId();

            counts.computeIfAbsent(leftBlockId, ignored -> new int[2])[0] += 1;
            counts.computeIfAbsent(rightBlockId, ignored -> new int[2])[1] += 1;
        }

        List<MaterialDeltaEntry> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if ("minecraft:air".equals(entry.getKey()) && entry.getValue()[0] == entry.getValue()[1]) {
                continue;
            }

            result.add(new MaterialDeltaEntry(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }

        result.sort(java.util.Comparator.comparingInt((MaterialDeltaEntry entry) -> Math.abs(entry.delta())).reversed()
                .thenComparing(MaterialDeltaEntry::blockId));
        return result;
    }
}
