package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.lighting.LightEngine;

/** Immutable block delta computed before the server-tick apply path. */
@SuppressWarnings("deprecation")
final class PreparedSectionDelta {
    private final short[] changedCells;
    private final short[] lightColumns;
    private final int[] heightmapIndexes;
    private final int[] poiIndexes;
    private final boolean lightChanged;
    private final boolean blockEntitiesChanged;

    private PreparedSectionDelta(
            short[] changedCells,
            short[] lightColumns,
            int[] heightmapIndexes,
            int[] poiIndexes,
            boolean lightChanged,
            boolean blockEntitiesChanged) {
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
        BitSet changed = new BitSet(SectionBlob.BLOCK_COUNT);
        short[] light = new short[256];
        int[] heightmaps = new int[256];
        Arrays.fill(heightmaps, -1);
        BitSet pois = new BitSet(SectionBlob.BLOCK_COUNT);
        boolean hasLight = false;
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            BlockState current = before.apply(index);
            BlockState replacement = target.stateAt(index);
            if (current.equals(replacement)) {
                continue;
            }
            changed.set(index);
            int x = index & 15;
            int z = (index >>> 4) & 15;
            int y = (index >>> 8) & 15;
            heightmaps[x | (z << 4)] = index;
            if (!PoiTypes.forState(current).equals(PoiTypes.forState(replacement))) {
                pois.set(index);
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
        short[] changedCells = new short[changed.cardinality()];
        int changedIndex = 0;
        for (int block = changed.nextSetBit(0);
                block >= 0;
                block = changed.nextSetBit(block + 1)) {
            changedCells[changedIndex++] = (short) (((block & 15) << 8)
                    | (((block >>> 4) & 15) << 4)
                    | ((block >>> 8) & 15));
        }
        return new PreparedSectionDelta(
                changedCells, light, Arrays.copyOf(heightmapIndexes, heightmapCount),
                pois.stream().toArray(),
                hasLight, blockEntitiesChanged);
    }

    short[] changedCells() {
        return changedCells;
    }

    int changedCount() {
        return changedCells.length;
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
