package io.github.luma.integration.axiom;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

final class AxiomBlockBufferExtractor {

    private static final int SECTION_SIZE = 16;

    public List<AxiomBlockMutation> extract(Object blockBuffer) {
        if (blockBuffer == null) {
            return List.of();
        }

        Collection<?> entries = this.entrySet(blockBuffer);
        if (entries.isEmpty()) {
            return List.of();
        }

        Object emptyState = this.emptyState(blockBuffer);
        List<AxiomBlockMutation> mutations = new ArrayList<>();
        for (Object entryObject : entries) {
            if (!(entryObject instanceof Long2ObjectMap.Entry<?> entry)) {
                continue;
            }
            if (!(entry.getValue() instanceof PalettedContainer<?> sectionStates)) {
                continue;
            }
            this.extractSectionMutations(entry.getLongKey(), sectionStates, emptyState, mutations);
        }
        return mutations;
    }

    private void extractSectionMutations(
            long sectionKey,
            PalettedContainer<?> sectionStates,
            Object emptyState,
            List<AxiomBlockMutation> mutations
    ) {
        int sectionBlockX = BlockPos.getX(sectionKey) << 4;
        int sectionBlockY = BlockPos.getY(sectionKey) << 4;
        int sectionBlockZ = BlockPos.getZ(sectionKey) << 4;
        for (int localY = 0; localY < SECTION_SIZE; localY++) {
            for (int localZ = 0; localZ < SECTION_SIZE; localZ++) {
                for (int localX = 0; localX < SECTION_SIZE; localX++) {
                    Object value = sectionStates.get(localX, localY, localZ);
                    if (value == emptyState || !(value instanceof BlockState newState)) {
                        continue;
                    }
                    mutations.add(new AxiomBlockMutation(
                            new BlockPos(sectionBlockX + localX, sectionBlockY + localY, sectionBlockZ + localZ),
                            newState,
                            null
                    ));
                }
            }
        }
    }

    private Collection<?> entrySet(Object blockBuffer) {
        try {
            Method entrySet = blockBuffer.getClass().getMethod("entrySet");
            Object value = entrySet.invoke(blockBuffer);
            return value instanceof Collection<?> collection ? collection : List.of();
        } catch (ReflectiveOperationException | LinkageError exception) {
            return List.of();
        }
    }

    private Object emptyState(Object blockBuffer) {
        try {
            Field field = blockBuffer.getClass().getField("EMPTY_STATE");
            return field.get(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }
}
