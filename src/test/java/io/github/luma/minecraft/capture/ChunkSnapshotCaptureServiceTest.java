package io.github.luma.minecraft.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSnapshotCaptureServiceTest {

    private final ChunkSnapshotCaptureService service = new ChunkSnapshotCaptureService();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void detectsMovingPistonAsTransientSnapshotState() {
        assertTrue(this.service.containsTransientBlockState(sectionWithDefault(
                Blocks.MOVING_PISTON.defaultBlockState()
        )));
    }

    @Test
    void stableSectionHasNoTransientSnapshotState() {
        assertFalse(this.service.containsTransientBlockState(sectionWithDefault(
                Blocks.OAK_PLANKS.defaultBlockState()
        )));
    }

    @Test
    void baselineOverridesBypassDetachedSectionBookkeeping() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/ChunkSnapshotCaptureService.java")
        );

        assertTrue(source.contains("if (!overrides.isEmpty())"));
        assertTrue(source.contains("capturedSection.getStates().set("));
        assertFalse(source.contains("sectionCopy.setBlockState("));
    }

    private static LevelChunkSection sectionWithDefault(BlockState state) {
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        return new LevelChunkSection(new PalettedContainer<>(state, strategy), null);
    }
}
