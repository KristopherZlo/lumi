package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftBlockStateDecoderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decodesEveryBlockAndBlockEntityNbtBeforeApply() throws Exception {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(17, "minecraft:chest");
        var source = new SectionBlob(states, Map.of(
                17, MinecraftNbtCodec.encode(blockEntityTag(
                        "minecraft:chest", "custom", "value"))));

        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);

        assertEquals(Blocks.STONE.defaultBlockState(), decoded.blockStates().getFirst());
        assertEquals(Blocks.CHEST.defaultBlockState(), decoded.blockStates().get(17));
        assertEquals("value", decoded.blockEntities().get(17).getStringOr("custom", ""));

        var current = new LevelChunkSection(new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        var replacement = decoded.replacementFor(current);
        assertEquals(Blocks.STONE.defaultBlockState(), replacement.getBlockState(0, 0, 0));
        assertEquals(decoded.blockStates().get(17), replacement.getBlockState(1, 0, 1));
        replacement.setBlockState(0, 0, 0, Blocks.DIRT.defaultBlockState(), false);
        assertEquals(Blocks.STONE.defaultBlockState(),
                decoded.replacementFor(current).getBlockState(0, 0, 0));
    }

    @Test
    void bulkPalettePreservesGlobalPaletteStatesAndCoordinates() {
        var palette = BuiltInRegistries.BLOCK.stream()
                .flatMap(block -> block.getStateDefinition().getPossibleStates().stream())
                .limit(300)
                .toList();
        var states = new ArrayList<BlockState>(SectionBlob.BLOCK_COUNT);
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            states.add(palette.get(index % palette.size()));
        }

        var current = new LevelChunkSection(new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        var replacement = new DecodedSection(states, Map.of()).replacementFor(current);

        for (int index = 0; index < states.size(); index++) {
            assertEquals(states.get(index), replacement.getBlockState(
                    index & 15, (index >>> 8) & 15, (index >>> 4) & 15));
        }
    }

    @Test
    void rejectsUnknownPersistentBlockStateDuringPreparation() {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(0, "missing:not_a_block");

        assertThrows(IOException.class, () ->
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                        .validate(new SectionBlob(states, Map.of())));
    }

    @Test
    void rejectsUnknownBlockEntityTypeDuringPreflight() throws Exception {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        var source = new SectionBlob(states, Map.of(
                0, MinecraftNbtCodec.encode(blockEntityTag(
                        "missing:not_a_block_entity", "custom", "value"))));

        assertThrows(IOException.class, () ->
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK).validate(source));
    }

    @Test
    void rejectsBlockEntityIncompatibleWithBlockStateDuringPreflight()
            throws Exception {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(0, "minecraft:air");
        var source = new SectionBlob(states, Map.of(
                0, MinecraftNbtCodec.encode(blockEntityTag(
                        "minecraft:command_block", "Command", "say invalid"))));

        assertThrows(IOException.class, () ->
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK).validate(source));
    }

    @Test
    void rejectsUnknownChangedBaseStateDuringDirectionalDecode() {
        var target = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        var before = new ArrayList<>(target);
        before.set(17, "missing:not_a_block");

        assertThrows(IOException.class, () ->
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK).decodeAgainst(
                        new SectionBlob(target, Map.of()),
                        new SectionBlob(before, Map.of())));
    }

    @Test
    void preparesOnlyTheHighestHeightmapChangePerColumn() throws Exception {
        var beforeStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        var targetStates = new ArrayList<>(beforeStates);
        targetStates.set((3 << 8) | (2 << 4) | 1, "minecraft:air");
        targetStates.set((10 << 8) | (2 << 4) | 1, "minecraft:dirt");
        targetStates.set((2 << 8) | (5 << 4) | 4, "minecraft:granite");
        var decoder = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK);

        DecodedSection target = decoder.decodeAgainst(
                new SectionBlob(targetStates, Map.of()),
                new SectionBlob(beforeStates, Map.of()));

        assertArrayEquals(new int[]{(10 << 8) | (2 << 4) | 1,
                        (2 << 8) | (5 << 4) | 4},
                target.preparedDelta().heightmapIndexes());
    }

    private static net.minecraft.nbt.CompoundTag tag(String key, String value) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(key, value);
        return tag;
    }

    private static net.minecraft.nbt.CompoundTag blockEntityTag(
            String type, String key, String value) {
        var tag = tag(key, value);
        tag.putString("id", type);
        return tag;
    }
}
