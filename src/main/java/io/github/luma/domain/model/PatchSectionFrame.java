package io.github.luma.domain.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

public record PatchSectionFrame(
        int chunkX,
        int chunkZ,
        int sectionY,
        long[] changedMask,
        List<CompoundTag> oldStatePalette,
        List<CompoundTag> newStatePalette,
        int[] oldStateIds,
        int[] newStateIds,
        List<CompoundTag> oldBlockEntityPalette,
        List<CompoundTag> newBlockEntityPalette,
        int[] oldBlockEntityIds,
        int[] newBlockEntityIds
) {

    public PatchSectionFrame {
        changedMask = changedMask == null ? new long[64] : changedMask.clone();
        oldStatePalette = copyPalette(oldStatePalette);
        newStatePalette = copyPalette(newStatePalette);
        oldStateIds = oldStateIds == null ? new int[0] : oldStateIds.clone();
        newStateIds = newStateIds == null ? new int[0] : newStateIds.clone();
        oldBlockEntityPalette = copyPalette(oldBlockEntityPalette);
        newBlockEntityPalette = copyPalette(newBlockEntityPalette);
        oldBlockEntityIds = oldBlockEntityIds == null ? new int[0] : oldBlockEntityIds.clone();
        newBlockEntityIds = newBlockEntityIds == null ? new int[0] : newBlockEntityIds.clone();
    }

    @Override
    public long[] changedMask() {
        return this.changedMask.clone();
    }

    @Override
    public int[] oldStateIds() {
        return this.oldStateIds.clone();
    }

    @Override
    public int[] newStateIds() {
        return this.newStateIds.clone();
    }

    @Override
    public int[] oldBlockEntityIds() {
        return this.oldBlockEntityIds.clone();
    }

    @Override
    public int[] newBlockEntityIds() {
        return this.newBlockEntityIds.clone();
    }

    private static List<CompoundTag> copyPalette(List<CompoundTag> palette) {
        if (palette == null || palette.isEmpty()) {
            return List.of();
        }
        return palette.stream()
                .map(tag -> tag == null ? new CompoundTag() : tag.copy())
                .toList();
    }
}
