package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
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
        HistoryCommandPayload amend = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.AMEND,
                "Improved clock", id('1'), 42);
        assertEquals(amend, roundTrip(HistoryCommandPayload.CODEC, amend));
        HistoryCommandPayload merge = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.MERGE,
                new MergeArgument("workspace/lab/idea", "Merge idea").encode(),
                id('1'), 42);
        assertEquals(merge, roundTrip(HistoryCommandPayload.CODEC, merge));
        var zoneCreate = new ZoneCreateArgument(
                "Clock", new io.github.lumi.domain.model.BlockBox(1, 2, 3, 4, 5, 6));
        HistoryCommandPayload createZone = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_CREATE,
                zoneCreate.encode(), id('1'), 42);
        assertEquals(createZone, roundTrip(HistoryCommandPayload.CODEC, createZone));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.ZONE_ENTER,
                HistoryCommandPayload.Kind.ZONE_LEAVE)) {
            HistoryCommandPayload zoneActor = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, UUID.randomUUID().toString(), id('1'), 42);
            assertEquals(zoneActor, roundTrip(HistoryCommandPayload.CODEC, zoneActor));
        }
        UUID zoneId = UUID.randomUUID();
        HistoryCommandPayload zoneSave = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_SAVE,
                new ZoneSaveArgument(zoneId, "Clock works").encode(), id('1'), 42);
        assertEquals(zoneSave, roundTrip(HistoryCommandPayload.CODEC, zoneSave));
        HistoryCommandPayload zoneRestore = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_RESTORE,
                new ZoneRestoreArgument(zoneId, id('2')).encode(), id('1'), 42);
        assertEquals(zoneRestore, roundTrip(HistoryCommandPayload.CODEC, zoneRestore));
        HistoryCommandPayload deleteVersion = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.DELETE_VERSION,
                id('2').hex(), id('1'), 42);
        assertEquals(deleteVersion,
                roundTrip(HistoryCommandPayload.CODEC, deleteVersion));
        HistoryCommandPayload cleanupVersion = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.CLEANUP_VERSION,
                id('2').hex(), id('1'), 42);
        assertEquals(cleanupVersion,
                roundTrip(HistoryCommandPayload.CODEC, cleanupVersion));
        OperationCancelPayload cancel = new OperationCancelPayload(
                UUID.randomUUID(), UUID.randomUUID());
        assertEquals(cancel, roundTrip(OperationCancelPayload.CODEC, cancel));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.QUICK_ROLLBACK,
                HistoryCommandPayload.Kind.UNDO,
                HistoryCommandPayload.Kind.REDO,
                HistoryCommandPayload.Kind.RECOVER_RESUME,
                HistoryCommandPayload.Kind.RECOVER_RETURN)) {
            HistoryCommandPayload live = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, "", id('2'), 43);
            assertEquals(live, roundTrip(HistoryCommandPayload.CODEC, live));
        }
        HistoryCommandPayload switchBranch = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_SWITCH,
                "workspace/lab/idea", id('2'), 43);
        assertEquals(switchBranch, roundTrip(HistoryCommandPayload.CODEC, switchBranch));
        HistoryCommandPayload createBranch = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_CREATE,
                "idea", id('2'), 43);
        assertEquals(createBranch, roundTrip(HistoryCommandPayload.CODEC, createBranch));
        HistoryCommandPayload restoreWithoutEntities = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_NO_ENTITIES,
                id('3').hex(), id('2'), 43);
        assertEquals(restoreWithoutEntities,
                roundTrip(HistoryCommandPayload.CODEC, restoreWithoutEntities));
        var partial = new PartialRestoreArgument(
                id('3'), new io.github.lumi.domain.model.BlockAreaTarget(
                        new io.github.lumi.domain.model.BlockBox(1, 2, 3, 4, 5, 6), true));
        HistoryCommandPayload restoreArea = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_AREA,
                partial.encode(), id('2'), 43);
        assertEquals(restoreArea, roundTrip(HistoryCommandPayload.CODEC, restoreArea));
        CompareArgument compare = new CompareArgument(id('1'), id('2'));
        HistoryCommandPayload compareCommand = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.COMPARE,
                compare.encode(), id('2'), 43);
        assertEquals(compareCommand, roundTrip(HistoryCommandPayload.CODEC, compareCommand));
        HistoryCommandPayload compareCancel = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.COMPARE_CANCEL,
                UUID.randomUUID().toString(), id('2'), 43);
        assertEquals(compareCancel, roundTrip(HistoryCommandPayload.CODEC, compareCancel));
    }

    @Test
    void restoreCommandRejectsInvalidTargetAndNegativeRevision() {
        UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.RESTORE, "not-a-commit", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.RESTORE_NO_ENTITIES,
                "not-a-commit", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.RESTORE_AREA,
                "not-an-area", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.SAVE, "Save", id('1'), -1));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.UNDO, "unexpected", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.BRANCH_CREATE, " ", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.COMPARE_CANCEL, "not-a-uuid", id('1'), 0));
    }

    @Test
    void snapshotAndTerminalEventRoundTrip() {
        HistorySnapshotPayload snapshot = new HistorySnapshotPayload(
                "minecraft:overworld", id('a'), 7, 3, true, true,
                new UUID(0, 7), "Redstone lab", "workspace/lab/main",
                java.util.List.of(new HistorySnapshotPayload.Version(
                        id('a'), "Clock works", "Builder", 1234,
                        CommitKind.MANUAL)),
                java.util.List.of(
                        new HistorySnapshotPayload.Branch("main", id('a'), true),
                        new HistorySnapshotPayload.Branch("idea", id('b'), false)),
                java.util.List.of(new HistorySnapshotPayload.ZoneView(
                        new UUID(0, 8), "Clock", 0xff336699, 4, 2, true,
                        java.util.List.of(new HistorySnapshotPayload.Version(
                                id('b'), "Clock v1", "Builder", 1235,
                                CommitKind.ZONE)))),
                java.util.List.of(new HistorySnapshotPayload.Version(
                        id('c'), "Old clock", "Builder", 1200,
                        CommitKind.MANUAL)));
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
