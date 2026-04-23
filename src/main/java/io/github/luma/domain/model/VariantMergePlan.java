package io.github.luma.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public record VariantMergePlan(
        String sourceProjectName,
        String sourceVariantId,
        String sourceHeadVersionId,
        String targetProjectName,
        String targetVariantId,
        String targetHeadVersionId,
        String commonAncestorVersionId,
        int sourceChangedBlocks,
        int targetChangedBlocks,
        List<StoredBlockChange> mergeChanges,
        List<MergeConflictZone> conflictZones
) {

    public boolean hasConflicts() {
        return !this.conflictZones.isEmpty();
    }

    public int mergeBlockCount() {
        return this.mergeChanges.size();
    }

    public List<BlockPoint> conflictPositions() {
        return this.conflictZones.stream()
                .flatMap(zone -> zone.positions().stream())
                .toList();
    }

    public int conflictChunkCount() {
        return this.conflictZones.stream()
                .flatMap(zone -> zone.chunks().stream())
                .map(chunk -> ((((long) chunk.x()) & 0xffffffffL) << 32) ^ (((long) chunk.z()) & 0xffffffffL))
                .collect(java.util.stream.Collectors.toSet())
                .size();
    }

    public List<BlockPoint> sampleConflictPositions(int limit) {
        return this.conflictPositions().stream().limit(Math.max(0, limit)).toList();
    }

    public int effectiveMergeBlockCount(List<MergeConflictZoneResolution> resolutions) {
        int count = this.mergeChanges.size();
        var resolutionMap = this.resolutionMap(resolutions);
        for (MergeConflictZone zone : this.conflictZones) {
            if (resolutionMap.get(zone.id()) == MergeConflictResolution.USE_IMPORTED) {
                count += zone.blockCount();
            }
        }
        return count;
    }

    public boolean canApply(List<MergeConflictZoneResolution> resolutions) {
        if (!this.hasConflicts()) {
            return !this.mergeChanges.isEmpty();
        }
        var resolutionMap = this.resolutionMap(resolutions);
        for (MergeConflictZone zone : this.conflictZones) {
            if (resolutionMap.get(zone.id()) == null) {
                return false;
            }
        }
        return this.effectiveMergeBlockCount(resolutions) > 0;
    }

    private LinkedHashMap<String, MergeConflictResolution> resolutionMap(List<MergeConflictZoneResolution> resolutions) {
        LinkedHashMap<String, MergeConflictResolution> resolutionMap = new LinkedHashMap<>();
        if (resolutions == null) {
            return resolutionMap;
        }
        for (MergeConflictZoneResolution resolution : resolutions) {
            if (resolution == null || resolution.zoneId() == null || resolution.zoneId().isBlank()) {
                continue;
            }
            resolutionMap.put(resolution.zoneId(), resolution.resolution());
        }
        return resolutionMap;
    }
}
