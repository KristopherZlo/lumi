package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/** Minecraft-native section payload decoded before tick-time apply. */
public final class DecodedSection {
    private static final Strategy<BlockState> BLOCK_STATES =
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    private final List<BlockState> blockStates;
    private final Map<Integer, CompoundTag> blockEntities;
    private final PalettedContainer<BlockState> preparedStates;

    public DecodedSection(
            List<BlockState> blockStates,
            Map<Integer, CompoundTag> blockEntities) {
        this.blockStates = List.copyOf(Objects.requireNonNull(blockStates, "blockStates"));
        this.blockEntities = Map.copyOf(
                Objects.requireNonNull(blockEntities, "blockEntities"));
        if (this.blockStates.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Decoded section must contain 4096 blocks");
        }
        preparedStates = prepare(this.blockStates);
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

    private static PalettedContainer<BlockState> prepare(List<BlockState> states) {
        var prepared = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), BLOCK_STATES);
        for (int index = 0; index < states.size(); index++) {
            prepared.set(
                    index & 15,
                    (index >>> 8) & 15,
                    (index >>> 4) & 15,
                    states.get(index));
        }
        return prepared;
    }
}
