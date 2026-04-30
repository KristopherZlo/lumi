package io.github.luma.minecraft.world;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

public record SectionBlockEntityPlan(
        Map<Integer, CompoundTag> blockEntities
) {

    public SectionBlockEntityPlan {
        blockEntities = copy(blockEntities);
    }

    public static SectionBlockEntityPlan empty() {
        return new SectionBlockEntityPlan(Map.of());
    }

    public boolean isEmpty() {
        return this.blockEntities.isEmpty();
    }

    public CompoundTag tagAt(int localIndex) {
        CompoundTag tag = this.blockEntities.get(localIndex);
        return tag == null ? null : tag.copy();
    }

    private static Map<Integer, CompoundTag> copy(Map<Integer, CompoundTag> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Integer, CompoundTag> copied = new LinkedHashMap<>();
        source.forEach((localIndex, tag) -> {
            if (localIndex != null && tag != null) {
                copied.put(localIndex, tag.copy());
            }
        });
        return Map.copyOf(copied);
    }
}
