package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostCallbackBlockMutationPolicyTest {

    private final PostCallbackBlockMutationPolicy policy = new PostCallbackBlockMutationPolicy();
    private final WorldMutationCapturePolicy capturePolicy = new WorldMutationCapturePolicy();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void consumedPlacementCancelsSynchronousRemovalInOneAction() {
        BlockPos pos = new BlockPos(1, 64, 1);
        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        accumulator.addBlockChange(change(pos, Blocks.TNT.defaultBlockState(), Blocks.AIR.defaultBlockState()));

        for (HistoryCaptureManager.BlockChangeInput input : this.policy.changesAfterCallbacks(
                pos,
                Blocks.AIR.defaultBlockState(),
                Blocks.TNT.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null,
                null
        )) {
            this.capturePolicy.capture(
                            WorldMutationSource.PLAYER,
                            input.pos(),
                            input.oldState(),
                            input.newState(),
                            input.oldBlockEntity(),
                            input.newBlockEntity()
                    )
                    .map(WorldMutationCapturePolicy.CapturedMutation::change)
                    .ifPresent(accumulator::addBlockChange);
        }

        assertTrue(accumulator.blockChanges().isEmpty());
    }

    @Test
    void consumedReplacementStillKeepsFinalWorldState() {
        BlockPos pos = new BlockPos(1, 64, 1);
        List<HistoryCaptureManager.BlockChangeInput> changes = this.policy.changesAfterCallbacks(
                pos,
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.TNT.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null,
                null
        );

        assertEquals(2, changes.size());
        assertEquals(Blocks.TNT.defaultBlockState(), changes.get(0).newState());
        assertEquals(Blocks.AIR.defaultBlockState(), changes.get(1).newState());
    }

    private static StoredBlockChange change(
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState
    ) {
        return new StoredBlockChange(
                BlockPoint.from(pos),
                StatePayload.capture(oldState, null),
                StatePayload.capture(newState, null)
        );
    }
}
