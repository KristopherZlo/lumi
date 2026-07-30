package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.lighting.LightEngine;

/** Immutable block delta computed before the server-tick apply path. */
@SuppressWarnings("deprecation")
final class PreparedSectionDelta {
    private final int[] changedIndexes;
    private final short[] changedCells;
    private final short[] lightColumns;
    private final int[] heightmapIndexes;
    private final int[] poiIndexes;
    private final boolean lightChanged;
    private final boolean blockEntitiesChanged;

    private PreparedSectionDelta(
            int[] changedIndexes,
            short[] changedCells,
            short[] lightColumns,
            int[] heightmapIndexes,
            int[] poiIndexes,
            boolean lightChanged,
            boolean blockEntitiesChanged) {
        this.changedIndexes = changedIndexes;
        this.changedCells = changedCells;
        this.lightColumns = lightColumns;
        this.heightmapIndexes = heightmapIndexes;
        this.poiIndexes = poiIndexes;
        this.lightChanged = lightChanged;
        this.blockEntitiesChanged = blockEntitiesChanged;
    }

    static PreparedSectionDelta between(
            SectionBlob source,
            SectionBlob before,
            List<BlockState> beforePalette,
            DecodedSection target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(beforePalette, "beforePalette");
        return create(index -> beforePalette.get(
                        before.palette().paletteIndex(index)),
                target, !before.blockEntities().equals(source.blockEntities()));
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
        int[] heightmaps = new int[256];
        Arrays.fill(heightmaps, -1);
        int[] pois = new int[SectionBlob.BLOCK_COUNT];
        boolean hasLight = false;
        int count = 0;
        int poiCount = 0;
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            BlockState current = before.apply(index);
            BlockState replacement = target.stateAt(index);
            if (current.equals(replacement)) {
                continue;
            }
            indexes[count] = index;
            int x = index & 15;
            int z = (index >>> 4) & 15;
            int y = (index >>> 8) & 15;
            cells[count++] = (short) ((x << 8) | (z << 4) | y);
            heightmaps[x | (z << 4)] = index;
            if (!PoiTypes.forState(current).equals(PoiTypes.forState(replacement))) {
                pois[poiCount++] = index;
            }
            if (requiresLightCheck(current, replacement)) {
                MinecraftSectionRewriter.markLightChange(light, x, y, z);
                hasLight = true;
            }
        }
        int[] heightmapIndexes = new int[256];
        int heightmapCount = 0;
        for (int index : heightmaps) {
            if (index >= 0) {
                heightmapIndexes[heightmapCount++] = index;
            }
        }
        return new PreparedSectionDelta(
                Arrays.copyOf(indexes, count), Arrays.copyOf(cells, count),
                light, Arrays.copyOf(heightmapIndexes, heightmapCount),
                Arrays.copyOf(pois, poiCount),
                hasLight, blockEntitiesChanged);
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

    int[] heightmapIndexes() {
        return heightmapIndexes;
    }

    int[] poiIndexes() {
        return poiIndexes;
    }

    boolean lightChanged() {
        return lightChanged;
    }

    boolean blockEntitiesChanged() {
        return blockEntitiesChanged;
    }

    private static boolean requiresLightCheck(BlockState current, BlockState target) {
        return LightEngine.hasDifferentLightProperties(current, target);
    }
}
