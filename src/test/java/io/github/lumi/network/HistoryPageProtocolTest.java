package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class HistoryPageProtocolTest {
    @Test
    void roundTripsWorkspaceAndZonePageContracts() {
        UUID request = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID zone = UUID.randomUUID();
        BranchName branch = new BranchName("idea/clock");
        var requestPayload = new HistoryPageRequestPayload(
                request, "minecraft:overworld", workspace, branch,
                Optional.of(zone), 24, 12);
        var version = new HistorySnapshotPayload.Version(
                id('1'), "Clock face", "Builder", 42,
                CommitKind.ZONE);
        var result = new HistoryPagePayload(
                request, "minecraft:overworld", workspace, branch,
                Optional.of(zone), 24, true, List.of(version), "");

        assertEquals(requestPayload,
                roundTrip(HistoryPageRequestPayload.CODEC, requestPayload));
        assertEquals(result, roundTrip(HistoryPagePayload.CODEC, result));
    }

    @Test
    void rejectsUnboundedPagesAndMixedFailureResults() {
        assertThrows(IllegalArgumentException.class,
                () -> new HistoryPageRequestPayload(
                        UUID.randomUUID(), "minecraft:overworld",
                        UUID.randomUUID(), new BranchName("main"),
                        Optional.empty(), 950, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new HistoryPagePayload(
                        UUID.randomUUID(), "minecraft:overworld",
                        UUID.randomUUID(), new BranchName("main"),
                        Optional.empty(), 0, true,
                        List.of(new HistorySnapshotPayload.Version(
                                id('1'), "Save", "Builder", 1,
                                CommitKind.MANUAL)),
                        "failed"));
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
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
