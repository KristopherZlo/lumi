package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        CaptureSessionState session = session();
        BlockPos pos = new BlockPos(5, 64, 9);

        this.coordinator.recordBaselineCorrection(session, pos, Blocks.MOVING_PISTON.defaultBlockState(), null);

        var corrections = session.baselineCorrections(List.of(ChunkPoint.from(BlockPoint.from(pos))));
        assertEquals("minecraft:air", corrections.get(BlockPoint.from(pos)).blockId());
    }

    @Test
    void preparedSnapshotSeedsSessionBaseline() {
        CaptureSessionState session = session();
        ChunkSnapshotPayload snapshot = chunkSnapshot();

        this.coordinator.captureSessionChunkBaseline(session, snapshot.chunk(), snapshot);

        assertSame(snapshot, session.baselineChunkState(snapshot.chunk()));
    }

    private static CaptureSessionState session() {
        return CaptureSessionState.create(TrackedChangeBuffer.create(
                "draft",
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                Instant.EPOCH
        ));
    }

    private static ChunkSnapshotPayload chunkSnapshot() {
        return new ChunkSnapshotPayload(
                0,
                0,
                0,
                15,
                List.of(new ChunkSectionSnapshotPayload(0, List.of(stateTag("minecraft:stone")), new long[64], 1)),
                Map.of()
        );
    }

    private static net.minecraft.nbt.CompoundTag stateTag(String blockId) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
