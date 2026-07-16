package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.Optional;
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
        OperationCancelPayload cancel = new OperationCancelPayload(
                UUID.randomUUID(), UUID.randomUUID());
        assertEquals(cancel, roundTrip(OperationCancelPayload.CODEC, cancel));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.QUICK_ROLLBACK,
                HistoryCommandPayload.Kind.UNDO,
                HistoryCommandPayload.Kind.REDO)) {
            HistoryCommandPayload live = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, "", id('2'), 43);
            assertEquals(live, roundTrip(HistoryCommandPayload.CODEC, live));
        }
    }

    @Test
    void restoreCommandRejectsInvalidTargetAndNegativeRevision() {
        UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.RESTORE, "not-a-commit", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.SAVE, "Save", id('1'), -1));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.UNDO, "unexpected", id('1'), 0));
    }

    @Test
    void snapshotAndTerminalEventRoundTrip() {
        HistorySnapshotPayload snapshot = new HistorySnapshotPayload(
                "minecraft:overworld", id('a'), 7, 3, true);
        OperationEventPayload event = new OperationEventPayload(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "minecraft:overworld", OperationEventPayload.State.ACCEPTED,
                "Queued", id('b'), 8,
                Optional.of(UUID.fromString("30000000-0000-0000-0000-000000000003")), 2);

        assertEquals(snapshot, roundTrip(HistorySnapshotPayload.CODEC, snapshot));
        assertEquals(event, roundTrip(OperationEventPayload.CODEC, event));
        OperationEventPayload progress = new OperationEventPayload(
                UUID.randomUUID(), "minecraft:overworld",
                OperationEventPayload.State.PROGRESS, "Restore: applying", id('b'), 8,
                Optional.of(UUID.randomUUID()), 0,
                Optional.of(new OperationProgress("Restore: applying", 4, 10)));
        assertEquals(progress, roundTrip(OperationEventPayload.CODEC, progress));
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
