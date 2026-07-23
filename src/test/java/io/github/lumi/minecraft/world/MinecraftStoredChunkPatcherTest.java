package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftStoredChunkPatcherTest {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static Strategy<BlockState> strategy;
    private static Codec<PalettedContainer<BlockState>> blockStates;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        blockStates = PalettedContainer.codecRW(
                BlockState.CODEC, strategy, Blocks.AIR.defaultBlockState());
    }

    @Test
    void patchesOnlyRestoreOwnedSerializedFields() throws Exception {
        ChunkPos position = new ChunkPos(2, -3);
        SectionKey key = new SectionKey(position.x, 0, position.z);
        List<String> beforeStates = sectionStates();
        List<String> targetStates = new ArrayList<>(beforeStates);
        int changed = index(1, 10, 2);
        targetStates.set(changed, "minecraft:air");
        DecodedSection target = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decodeAgainst(
                        new SectionBlob(targetStates, Map.of()),
                        new SectionBlob(beforeStates, Map.of()));
        CompoundTag source = storedChunk(position, beforeStates, changed);

        MinecraftStoredChunkPatcher.Patch patched =
                new MinecraftStoredChunkPatcher(blockStates, MIN_Y, HEIGHT)
                        .patch(position, source, Map.of(key, target));

        assertEquals("keep-me", patched.tag().getStringOr("custom", ""));
        CompoundTag section = patched.tag().getListOrEmpty("sections")
                .getCompound(0).orElseThrow();
        assertEquals("keep-biomes", section.getCompoundOrEmpty("biomes")
                .getStringOr("custom", ""));
        assertFalse(section.contains("BlockLight"));
        assertFalse(section.contains("SkyLight"));
        assertFalse(patched.tag().contains("isLightOn"));
        PalettedContainer<BlockState> states =
                section.read("block_states", blockStates).orElseThrow();
        assertEquals(Blocks.AIR.defaultBlockState(), states.get(1, 10, 2));
        assertEquals(Blocks.STONE.defaultBlockState(), states.get(1, 9, 2));

        long[] raw = patched.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
        SimpleBitStorage heightmap = new SimpleBitStorage(
                Mth.ceillog2(HEIGHT + 1), 256, raw);
        assertEquals(9 - MIN_Y + 1, heightmap.get(column(1, 2)));
        assertEquals(10 - MIN_Y + 1, heightmap.get(column(4, 5)));

        ListTag blockEntities = patched.tag().getListOrEmpty("block_entities");
        assertEquals(1, blockEntities.size());
        assertEquals(-1, blockEntities.getCompoundOrEmpty(0).getIntOr("y", 0));
    }

    @Test
    void rejectsMissingTargetSectionBeforeMutatingStoredNbt() throws Exception {
        ChunkPos position = new ChunkPos(2, -3);
        List<String> states = sectionStates();
        CompoundTag source = storedChunk(position, states, index(1, 10, 2));
        SectionKey absent = new SectionKey(position.x, 1, position.z);
        DecodedSection target = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decodeAgainst(
                        new SectionBlob(states, Map.of()),
                        new SectionBlob(states, Map.of()));

        assertThrows(MinecraftStoredChunkPatcher.UnsupportedChunk.class, () ->
                new MinecraftStoredChunkPatcher(blockStates, MIN_Y, HEIGHT)
                        .patch(position, source, Map.of(absent, target)));

        assertTrue(source.getListOrEmpty("sections")
                .getCompoundOrEmpty(0).contains("BlockLight"));
        assertTrue(source.contains("isLightOn"));
        assertEquals(2, source.getListOrEmpty("block_entities").size());
    }

    private static CompoundTag storedChunk(
            ChunkPos position, List<String> states, int changedColumnIndex) {
        CompoundTag root = new CompoundTag();
        root.putInt("xPos", position.x);
        root.putInt("zPos", position.z);
        root.putString("custom", "keep-me");
        root.putBoolean("isLightOn", true);
        root.store("Status", ChunkStatus.CODEC, ChunkStatus.FULL);

        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.store("block_states", blockStates, palette(states));
        CompoundTag biomes = new CompoundTag();
        biomes.putString("custom", "keep-biomes");
        section.put("biomes", biomes);
        section.putByteArray("BlockLight", new byte[2_048]);
        section.putByteArray("SkyLight", new byte[2_048]);
        ListTag sections = new ListTag();
        sections.add(section);
        root.put("sections", sections);

        SimpleBitStorage heightmap =
                new SimpleBitStorage(Mth.ceillog2(HEIGHT + 1), 256);
        heightmap.set(column(1, 2), 10 - MIN_Y + 1);
        heightmap.set(column(4, 5), 10 - MIN_Y + 1);
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray(
                Heightmap.Types.WORLD_SURFACE.getSerializationKey(),
                heightmap.getRaw());
        root.put("Heightmaps", heightmaps);

        ListTag blockEntities = new ListTag();
        blockEntities.add(blockEntity(
                position.getMinBlockX() + (changedColumnIndex & 15), 1,
                position.getMinBlockZ() + ((changedColumnIndex >>> 4) & 15)));
        blockEntities.add(blockEntity(
                position.getMinBlockX(), -1, position.getMinBlockZ()));
        root.put("block_entities", blockEntities);
        return root;
    }

    private static List<String> sectionStates() {
        List<String> states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int y = 0; y <= 10; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    states.set(index(x, y, z), "minecraft:stone");
                }
            }
        }
        return states;
    }

    private static PalettedContainer<BlockState> palette(List<String> states) {
        PalettedContainer<BlockState> palette = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), strategy);
        for (int index = 0; index < states.size(); index++) {
            palette.set(index & 15, (index >>> 8) & 15, (index >>> 4) & 15,
                    states.get(index).equals("minecraft:stone")
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
        }
        return palette;
    }

    private static CompoundTag blockEntity(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int column(int x, int z) {
        return x | (z << 4);
    }
}
