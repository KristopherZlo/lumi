package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSectionMutationCaptureServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsExternalToolStackDetectionWhenSectionHasNoServerOwner() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        DirectSectionMutationCaptureService service = new DirectSectionMutationCaptureService(
                () -> {
                    inspectedStack.set(true);
                    return Optional.empty();
                },
                section -> Optional.empty()
        );

        service.captureBefore(null, 0, 0, 0);

        assertFalse(inspectedStack.get());
    }

    @Test
    void capturesCurrentPistonSourceWithoutExternalToolStackInspection() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        DirectSectionMutationCaptureService service = new DirectSectionMutationCaptureService(
                () -> {
                    inspectedStack.set(true);
                    return Optional.empty();
                },
                section -> Optional.of(new ChunkSectionOwnershipRegistry.SectionOwner(
                        null,
                        new ChunkPos(2, -3),
                        4
                ))
        );
        LevelChunkSection section = sectionWithDefault(Blocks.STONE.defaultBlockState());

        DirectSectionMutationCaptureService.PendingDirectSectionMutation mutation;
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.PISTON)) {
            mutation = service.captureBefore(section, 1, 2, 3);
        }

        assertFalse(inspectedStack.get());
        assertTrue(mutation.currentSource());
        assertNull(mutation.operation());
        assertEquals(new BlockPos(33, 66, -45), mutation.pos());
    }

    private static LevelChunkSection sectionWithDefault(BlockState state) {
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        return new LevelChunkSection(new PalettedContainer<>(state, strategy), null);
    }
}
