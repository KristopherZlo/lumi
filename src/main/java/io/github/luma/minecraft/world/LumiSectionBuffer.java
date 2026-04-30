package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public final class LumiSectionBuffer {

    private final int sectionY;
    private final SectionChangeMask changedCells;
    private final BlockState[] targetStates;
    private final SectionBlockEntityPlan blockEntityPlan;

    private LumiSectionBuffer(
            int sectionY,
            SectionChangeMask changedCells,
            BlockState[] targetStates,
            SectionBlockEntityPlan blockEntityPlan
    ) {
        this.sectionY = sectionY;
        this.changedCells = changedCells == null ? SectionChangeMask.empty() : changedCells;
        this.targetStates = copyTargetStates(targetStates);
        this.blockEntityPlan = blockEntityPlan == null ? SectionBlockEntityPlan.empty() : blockEntityPlan;
        this.validateChangedTargets();
    }

    public static Builder builder(int sectionY) {
        return new Builder(sectionY);
    }

    public int sectionY() {
        return this.sectionY;
    }

    public SectionChangeMask changedCells() {
        return this.changedCells;
    }

    public int changedCellCount() {
        return this.changedCells.cardinality();
    }

    public BlockState targetStateAt(int localIndex) {
        if (localIndex < 0 || localIndex >= SectionChangeMask.ENTRY_COUNT) {
            return null;
        }
        return this.targetStates[localIndex];
    }

    public BlockState targetStateAt(int localX, int localY, int localZ) {
        return this.targetStateAt(SectionChangeMask.localIndex(localX, localY, localZ));
    }

    public SectionBlockEntityPlan blockEntityPlan() {
        return this.blockEntityPlan;
    }

    public boolean hasBlockEntities() {
        return !this.blockEntityPlan.isEmpty();
    }

    List<PreparedBlockPlacement> toPlacements(ChunkPoint chunk) {
        List<PreparedBlockPlacement> placements = new ArrayList<>(this.changedCellCount());
        this.changedCells.forEachSetCell(localIndex -> {
            BlockState state = this.targetStateAt(localIndex);
            if (state == null) {
                return;
            }
            int localX = SectionChangeMask.localX(localIndex);
            int localY = SectionChangeMask.localY(localIndex);
            int localZ = SectionChangeMask.localZ(localIndex);
            CompoundTag blockEntityTag = this.blockEntityPlan.tagAt(localIndex);
            placements.add(new PreparedBlockPlacement(
                    new BlockPos((chunk.x() << 4) + localX, (this.sectionY << 4) + localY, (chunk.z() << 4) + localZ),
                    state,
                    blockEntityTag
            ));
        });
        return placements;
    }

    private void validateChangedTargets() {
        this.changedCells.forEachSetCell(localIndex -> {
            if (this.targetStates[localIndex] == null) {
                throw new IllegalArgumentException("Missing target state for changed section cell " + localIndex);
            }
        });
    }

    private static BlockState[] copyTargetStates(BlockState[] targetStates) {
        BlockState[] copied = new BlockState[SectionChangeMask.ENTRY_COUNT];
        if (targetStates != null) {
            System.arraycopy(targetStates, 0, copied, 0, Math.min(targetStates.length, copied.length));
        }
        return copied;
    }

    public static final class Builder {

        private final int sectionY;
        private final SectionChangeMask.Builder maskBuilder = SectionChangeMask.builder();
        private final BlockState[] targetStates = new BlockState[SectionChangeMask.ENTRY_COUNT];
        private final Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();

        private Builder(int sectionY) {
            this.sectionY = sectionY;
        }

        public Builder set(int localIndex, BlockState state, CompoundTag blockEntityTag) {
            if (state == null) {
                throw new IllegalArgumentException("state is required for section cell " + localIndex);
            }
            this.maskBuilder.set(localIndex);
            this.targetStates[localIndex] = state;
            if (blockEntityTag != null) {
                this.blockEntities.put(localIndex, blockEntityTag.copy());
            }
            return this;
        }

        public Builder set(int localX, int localY, int localZ, BlockState state, CompoundTag blockEntityTag) {
            return this.set(SectionChangeMask.localIndex(localX, localY, localZ), state, blockEntityTag);
        }

        public LumiSectionBuffer build() {
            return new LumiSectionBuffer(
                    this.sectionY,
                    this.maskBuilder.build(),
                    this.targetStates,
                    new SectionBlockEntityPlan(this.blockEntities)
            );
        }
    }
}
