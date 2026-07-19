package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class PendingStatisticsProtocolTest {
    @Test
    void roundTripsCorrelatedWorkspaceAndZoneTotals() {
        UUID requestId = new UUID(0, 1);
        UUID workspaceId = new UUID(0, 2);
        UUID zoneId = new UUID(0, 3);
        CommitId head = new CommitId(new ObjectId("1".repeat(64)));
        var request = new PendingStatisticsRequestPayload(
                requestId, "minecraft:overworld", workspaceId, head, 4);
        var result = new PendingStatisticsPayload(
                requestId, "minecraft:overworld", workspaceId, head, 4,
                new PendingChangeStatistics(10, 2, 7),
                Map.of(zoneId, new PendingChangeStatistics(3, 1, 2)), "");

        assertEquals(request, roundTrip(
                PendingStatisticsRequestPayload.CODEC, request));
        assertEquals(result, roundTrip(
                PendingStatisticsPayload.CODEC, result));
    }

    @Test
    void failureCannotCarrySuccessfulTotals() {
        assertThrows(IllegalArgumentException.class, () ->
                new PendingStatisticsPayload(
                        UUID.randomUUID(), "minecraft:overworld",
                        UUID.randomUUID(),
                        new CommitId(new ObjectId("2".repeat(64))), 0,
                        new PendingChangeStatistics(1, 0, 0),
                        Map.of(), "failed"));
    }

    private static <T> T roundTrip(
            StreamCodec<FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
