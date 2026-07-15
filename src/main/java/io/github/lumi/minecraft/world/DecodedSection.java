package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft-native section payload decoded before tick-time apply. */
public record DecodedSection(
        List<BlockState> blockStates,
        Map<Integer, CompoundTag> blockEntities) {
    public DecodedSection {
        blockStates = List.copyOf(Objects.requireNonNull(blockStates, "blockStates"));
        blockEntities = Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
        if (blockStates.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Decoded section must contain 4096 blocks");
        }
    }
}
