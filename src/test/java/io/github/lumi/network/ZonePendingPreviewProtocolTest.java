package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.WorkingIndexPreview;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ZonePendingPreviewProtocolTest {
    @Test
    void roundTripsContextAndNegativeCoordinates() {
        var payload = payload(List.of(new HistorySnapshotPayload.PendingBlock(-17, -64, 100)), "");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ZonePendingPreviewPayload.CODEC.encode(buffer, payload);
            assertEquals(payload, ZonePendingPreviewPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedOrFailedGeometry() {
        var point = new HistorySnapshotPayload.PendingBlock(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () ->
                payload(Collections.nCopies(513, point), ""));
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(point), "Failed"));
        assertTrue(payload(List.of(), "Failed").blocks().isEmpty());
    }

    @Test
    void validatesTheRequestedZoneRevision() {
        var scope = new ZonePendingPreviewArgument(new UUID(0, 1), 2);
        assertEquals(scope, ZonePendingPreviewArgument.parse(scope.encode()));
        for (String invalid : List.of("", scope.zoneId() + "\n-1",
                scope.zoneId() + "\n1\n2", "bad\n1")) {
            assertThrows(IllegalArgumentException.class, () -> ZonePendingPreviewArgument.parse(invalid));
        }
    }

    @Test
    void mapsOnlyScopedCoordinatesAndDropsThemOnFailure() {
        var scope = new ZonePendingPreviewArgument(new UUID(0, 2), 3);
        var command = new HistoryCommandPayload(new UUID(0, 1),
                HistoryCommandPayload.Kind.ZONE_PENDING_PREVIEW, scope.encode(),
                CommitId.hash(new byte[] {1}), 4);
        var preview = new WorkingIndexPreview(1, List.of(), List.of(new BlockPosition(-17, 2, 3)));
        var result = ZonePendingPreviewCommandHandler.response(command, "minecraft:overworld",
                new UUID(0, 5), scope, 6, preview, "");
        assertEquals(List.of(new HistorySnapshotPayload.PendingBlock(-17, 2, 3)), result.blocks());
        assertEquals(scope.expectedRevision(), result.zoneRevision());
        assertTrue(ZonePendingPreviewCommandHandler.response(command, "minecraft:overworld",
                new UUID(0, 5), scope, 6, null, "Deleted").blocks().isEmpty());
    }

    private static ZonePendingPreviewPayload payload(
            List<HistorySnapshotPayload.PendingBlock> blocks, String error) {
        return new ZonePendingPreviewPayload(new UUID(0, 1), "minecraft:overworld", new UUID(0, 2),
                CommitId.hash(new byte[] {1}), 3, new UUID(0, 4), 5, 6, blocks, error);
    }
}
