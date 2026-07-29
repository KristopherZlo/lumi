package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/** Minecraft-native section payload decoded before tick-time apply. */
public final class DecodedSection {
    private static final Strategy<BlockState> BLOCK_STATES =
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    private final List<BlockState> palette;
    private final short[] paletteIndexes;
    private final List<BlockState> blockStates;
    private final Map<Integer, CompoundTag> blockEntities;
    private final PalettedContainer<BlockState> preparedStates;
    private final PreparedSectionDelta delta;

    public DecodedSection(
            List<BlockState> blockStates,
            Map<Integer, CompoundTag> blockEntities) {
        this(compact(blockStates), blockEntities);
    }

    private DecodedSection(
            NativePalette states,
            Map<Integer, CompoundTag> blockEntities) {
        this(states.palette(), states.indexes(),
                new PaletteBlockStates(states.palette(), states.indexes()),
                Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities")),
                prepare(states.palette(), states.indexes()), null);
    }

    static DecodedSection fromPalette(
            List<BlockState> palette,
            short[] indexes,
            Map<Integer, CompoundTag> blockEntities) {
        return new DecodedSection(new NativePalette(palette, indexes), blockEntities);
    }

    private DecodedSection(
            List<BlockState> palette,
            short[] paletteIndexes,
            List<BlockState> blockStates,
            Map<Integer, CompoundTag> blockEntities,
            PalettedContainer<BlockState> preparedStates,
            PreparedSectionDelta delta) {
        this.palette = palette;
        this.paletteIndexes = paletteIndexes;
        this.blockStates = blockStates;
        this.blockEntities = blockEntities;
        this.preparedStates = preparedStates;
        this.delta = delta;
        if (this.blockStates.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Decoded section must contain 4096 blocks");
        }
    }

    public List<BlockState> blockStates() {
        return blockStates;
    }

    public Map<Integer, CompoundTag> blockEntities() {
        return blockEntities;
    }

    LevelChunkSection replacementFor(LevelChunkSection current) {
        Objects.requireNonNull(current, "current");
        return new LevelChunkSection(preparedStates.copy(), current.getBiomes());
    }

    PalettedContainer<BlockState> copyBlockStates() {
        return preparedStates.copy();
    }

    DecodedSection prepareAgainst(
            SectionBlob source,
            SectionBlob before,
            MinecraftBlockStateDecoder decoder) throws IOException {
        List<BlockState> beforePalette = decoder.decodePalette(before);
        decoder.validateBlockEntities(before, beforePalette);
        return new DecodedSection(
                palette, paletteIndexes, blockStates, blockEntities, preparedStates,
                PreparedSectionDelta.between(
                        source, before, beforePalette, this));
    }

    PreparedSectionDelta deltaFrom(LevelChunkSection current) {
        return delta == null ? PreparedSectionDelta.inspect(current, this) : delta;
    }

    boolean hasPreparedDelta() {
        return delta != null;
    }

    PreparedSectionDelta preparedDelta() {
        if (delta == null) {
            throw new IllegalStateException("Section has no directional Restore delta");
        }
        return delta;
    }

    BlockState stateAt(int index) {
        return palette.get(Short.toUnsignedInt(paletteIndexes[index]));
    }

    private static NativePalette compact(List<BlockState> states) {
        Objects.requireNonNull(states, "blockStates");
        if (states.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "Decoded section must contain 4096 blocks");
        }
        var palette = new ArrayList<BlockState>();
        var paletteIndexes = new IdentityHashMap<BlockState, Integer>();
        var indexes = new short[SectionBlob.BLOCK_COUNT];
        for (int index = 0; index < states.size(); index++) {
            BlockState state = states.get(index);
            Integer paletteIndex = paletteIndexes.get(state);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteIndexes.put(state, paletteIndex);
                palette.add(state);
            }
            indexes[index] = paletteIndex.shortValue();
        }
        return new NativePalette(palette, indexes);
    }

    private static PalettedContainer<BlockState> prepare(
            List<BlockState> palette, short[] indexes) {
        int bits = palette.size() == 1
                ? 0
                : Math.max(4, Mth.ceillog2(palette.size()));
        Optional<java.util.stream.LongStream> storage = Optional.empty();
        if (bits != 0) {
            var packed = new SimpleBitStorage(bits, indexes.length);
            for (int index = 0; index < indexes.length; index++) {
                packed.set(index, Short.toUnsignedInt(indexes[index]));
            }
            storage = Optional.of(Arrays.stream(packed.getRaw()));
        }
        return PalettedContainer.unpack(BLOCK_STATES,
                new PalettedContainerRO.PackedData<>(palette, storage, bits))
                .getOrThrow(message -> new IllegalStateException(
                        "Failed to prepare decoded section: " + message));
    }

    private record NativePalette(List<BlockState> palette, short[] indexes) {
        private NativePalette {
            palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
            indexes = Objects.requireNonNull(indexes, "indexes").clone();
            if (palette.isEmpty() || palette.size() > SectionBlob.BLOCK_COUNT
                    || indexes.length != SectionBlob.BLOCK_COUNT) {
                throw new IllegalArgumentException("Invalid decoded section palette");
            }
            for (short index : indexes) {
                if (Short.toUnsignedInt(index) >= palette.size()) {
                    throw new IllegalArgumentException(
                            "Decoded block references missing palette entry");
                }
            }
        }
    }

    private static final class PaletteBlockStates extends AbstractList<BlockState>
            implements java.util.RandomAccess {
        private final List<BlockState> palette;
        private final short[] indexes;

        private PaletteBlockStates(List<BlockState> palette, short[] indexes) {
            this.palette = palette;
            this.indexes = indexes;
        }

        @Override
        public BlockState get(int index) {
            return palette.get(Short.toUnsignedInt(indexes[index]));
        }

        @Override
        public int size() {
            return SectionBlob.BLOCK_COUNT;
        }
    }
}
