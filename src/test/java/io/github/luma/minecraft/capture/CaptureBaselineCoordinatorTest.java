package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaptureBaselineCoordinatorTest {

    private final CaptureBaselineCoordinator coordinator = new CaptureBaselineCoordinator();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void baselineCorrectionStoresPersistentOriginalState() {
        CaptureSessionState session = CaptureSessionState.create(TrackedChangeBuffer.create(
                "draft",
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                Instant.EPOCH
        ));
        BlockPos pos = new BlockPos(5, 64, 9);

        this.coordinator.recordBaselineCorrection(session, pos, Blocks.MOVING_PISTON.defaultBlockState(), null);

        var corrections = session.baselineCorrections(List.of(ChunkPoint.from(BlockPoint.from(pos))));
        assertEquals("minecraft:air", corrections.get(BlockPoint.from(pos)).blockId());
    }
}
