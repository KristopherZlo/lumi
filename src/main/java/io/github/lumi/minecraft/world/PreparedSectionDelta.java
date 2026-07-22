package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Immutable block delta computed before the server-tick apply path. */
@SuppressWarnings("deprecation")
final class PreparedSectionDelta {
    private final int[] changedIndexes;
    private final short[] changedCells;
    private final short[] lightColumns;
    private final boolean lightChanged;
    private final boolean blockEntitiesChanged;

    private PreparedSectionDelta(
            int[] changedIndexes,
            short[] changedCells,
            short[] lightColumns,
            boolean lightChanged,
            boolean blockEntitiesChanged) {
        this.changedIndexes = changedIndexes;
        this.changedCells = changedCells;
        this.lightColumns = lightColumns;
        this.lightChanged = lightChanged;
        this.blockEntitiesChanged = blockEntitiesChanged;
    }

    static PreparedSectionDelta between(DecodedSection before, DecodedSection target) {
        Objects.requireNonNull(before, "before");
        return create(before.blockStates()::get, target,
                !before.blockEntities().equals(target.blockEntities()));
    }

    static PreparedSectionDelta inspect(
            LevelChunkSection before, DecodedSection target) {
        Objects.requireNonNull(before, "before");
        return create(index -> before.getBlockState(
                index & 15, (index >>> 8) & 15, (index >>> 4) & 15),
                target, true);
    }

    private static PreparedSectionDelta create(
            java.util.function.IntFunction<BlockState> before,
            DecodedSection target,
            boolean blockEntitiesChanged) {
        int[] indexes = new int[SectionBlob.BLOCK_COUNT];
        short[] cells = new short[SectionBlob.BLOCK_COUNT];
        short[] light = new short[256];
        boolean hasLight = false;
        int count = 0;
        List<BlockState> targetStates = target.blockStates();
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            BlockState current = before.apply(index);
            BlockState replacement = targetStates.get(index);
            if (current.equals(replacement)) {
                continue;
            }
            indexes[count] = index;
            int x = index & 15;
            int z = (index >>> 4) & 15;
            int y = (index >>> 8) & 15;
            cells[count++] = (short) ((x << 8) | (z << 4) | y);
            if (requiresLightCheck(current, replacement)) {
                MinecraftSectionRewriter.markLightChange(light, x, y, z);
                hasLight = true;
            }
        }
        return new PreparedSectionDelta(
                Arrays.copyOf(indexes, count), Arrays.copyOf(cells, count),
                light, hasLight, blockEntitiesChanged);
    }

    int[] changedIndexes() {
        return changedIndexes;
    }

    short[] changedCells() {
        return changedCells;
    }

    short[] lightColumns() {
        return lightColumns;
    }

    boolean lightChanged() {
        return lightChanged;
    }

    boolean blockEntitiesChanged() {
        return blockEntitiesChanged;
    }

    private static boolean requiresLightCheck(BlockState current, BlockState target) {
        return current.getLightEmission() != target.getLightEmission()
                || current.getLightBlock() != target.getLightBlock()
                || current.useShapeForLightOcclusion() != target.useShapeForLightOcclusion()
                || current.propagatesSkylightDown() != target.propagatesSkylightDown()
                || current.canOcclude() != target.canOcclude()
                || current.blocksMotion() != target.blocksMotion()
                || !current.getFluidState().equals(target.getFluidState());
    }
}
