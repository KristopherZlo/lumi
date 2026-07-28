package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
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

    private final List<BlockState> blockStates;
    private final Map<Integer, CompoundTag> blockEntities;
    private final PalettedContainer<BlockState> preparedStates;
    private final PreparedSectionDelta delta;

    public DecodedSection(
            List<BlockState> blockStates,
            Map<Integer, CompoundTag> blockEntities) {
        this(List.copyOf(Objects.requireNonNull(blockStates, "blockStates")),
                Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities")),
                prepare(blockStates), null);
    }

    private DecodedSection(
            List<BlockState> blockStates,
            Map<Integer, CompoundTag> blockEntities,
            PalettedContainer<BlockState> preparedStates,
            PreparedSectionDelta delta) {
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
        return new DecodedSection(
                blockStates, blockEntities, preparedStates,
                PreparedSectionDelta.between(source, before, decoder, this));
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

    private static PalettedContainer<BlockState> prepare(List<BlockState> states) {
        if (states.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Decoded section must contain 4096 blocks");
        }
        var palette = new ArrayList<BlockState>();
        var paletteIndexes = new IdentityHashMap<BlockState, Integer>();
        var indexes = new int[SectionBlob.BLOCK_COUNT];
        for (int index = 0; index < states.size(); index++) {
            BlockState state = states.get(index);
            Integer paletteIndex = paletteIndexes.get(state);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteIndexes.put(state, paletteIndex);
                palette.add(state);
            }
            indexes[index] = paletteIndex;
        }
        int bits = palette.size() == 1
                ? 0
                : Math.max(4, Mth.ceillog2(palette.size()));
        var storage = bits == 0
                ? Optional.<java.util.stream.LongStream>empty()
                : Optional.of(Arrays.stream(
                        new SimpleBitStorage(bits, indexes.length, indexes).getRaw()));
        return PalettedContainer.unpack(BLOCK_STATES,
                new PalettedContainerRO.PackedData<>(palette, storage, bits))
                .getOrThrow(message -> new IllegalStateException(
                        "Failed to prepare decoded section: " + message));
    }
}
