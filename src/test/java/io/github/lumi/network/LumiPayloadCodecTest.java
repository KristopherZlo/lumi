package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class LumiPayloadCodecTest {
    @Test
    void commandRoundTripsExpectedRefAndArgument() {
        HistoryCommandPayload command = new HistoryCommandPayload(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                HistoryCommandPayload.Kind.SAVE, "Builder idea", id('1'), 42);

        assertEquals(command, roundTrip(HistoryCommandPayload.CODEC, command));
    }

    @Test
    void restoreCommandRejectsInvalidTargetAndNegativeRevision() {
        UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.RESTORE, "not-a-commit", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.SAVE, "Save", id('1'), -1));
    }

    @Test
    void snapshotAndTerminalEventRoundTrip() {
        HistorySnapshotPayload snapshot = new HistorySnapshotPayload(
                "minecraft:overworld", id('a'), 7, 3, true);
        OperationEventPayload event = new OperationEventPayload(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "minecraft:overworld", OperationEventPayload.State.RETURNED,
                "Target failed verification; returned safely", id('b'), 8);

        assertEquals(snapshot, roundTrip(HistorySnapshotPayload.CODEC, snapshot));
        assertEquals(event, roundTrip(OperationEventPayload.CODEC, event));
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
