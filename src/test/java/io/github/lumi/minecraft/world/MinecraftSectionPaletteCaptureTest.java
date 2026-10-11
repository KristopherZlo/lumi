package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.object.SectionBlobCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MinecraftSectionPaletteCaptureTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 17, 257, 4096})
    void everyNativePaletteModePreservesCanonicalPayloadAndSnapshot(int distinct) throws Exception {
        var section = section();
        List<BlockState> states = new ArrayList<>();
        Block.BLOCK_STATE_REGISTRY.forEach(states::add);
        List<String> expected = new ArrayList<>();
        for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
            BlockState state = states.get(cell % distinct);
            section.setBlockState(cell & 15, cell >>> 8, (cell >>> 4) & 15, state, false);
            expected.add(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
        }
        var capture = new MinecraftSectionPaletteCapture();
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "minecraft:chest");
        var entities = Map.of(731, MinecraftNbtCodec.encode(tag));
        var actual = capture.capture(section, entities);
        var codec = new SectionBlobCodec();
        byte[] canonical = codec.encode(new SectionBlob(expected, entities));
        assertArrayEquals(canonical, codec.encode(actual));
        assertEquals(ObjectId.hash(canonical), ObjectId.hash(codec.encode(actual)));
        section.setBlockState(0, 0, 0, Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
        assertArrayEquals(canonical, codec.encode(actual));
        assertEquals(actual, codec.decode(canonical));
    }

    @Test
    void staleNativePaletteEntriesAreExcludedAndLockIsReleasedOnFailure() {
        var section = section();
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
        var capture = new MinecraftSectionPaletteCapture();
        assertThrows(NullPointerException.class, () -> capture.capture(section, null));
        assertEquals(List.of("minecraft:air"), capture.capture(section, Map.of()).palette().palette());
    }

    private static LevelChunkSection section() {
        return new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
    }
}
