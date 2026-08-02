package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.HudDisplayMode;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class LumiPayloadCodecTest {
    @Test
    void workspaceSettingsArgumentIsCanonicalAndStrict() {
        var argument = new WorkspaceSettingsArgument(
                new WorkspaceSettings(
                        false, true, false, HudDisplayMode.BOSSBAR, true));

        assertEquals("0,1,0,0,1", argument.encode());
        assertEquals(argument, WorkspaceSettingsArgument.parse(argument.encode()));
        assertEquals(argument.settings(),
                WorkspaceSettingsArgument.parse(argument.encode()).settings());
        assertEquals(new WorkspaceSettings(
                        false, true, true, HudDisplayMode.GUI, false),
                WorkspaceSettingsArgument.parse("0,1").settings());
        assertEquals(new WorkspaceSettings(
                        false, true, true, HudDisplayMode.GUI, false),
                WorkspaceSettingsArgument.parse("0,1,1,1").settings());
        assertEquals(HudDisplayMode.BOSSBAR,
                WorkspaceSettingsArgument.parse("0,1,1,0").hudDisplayMode());
        assertEquals(HudDisplayMode.NONE,
                WorkspaceSettingsArgument.parse("0,1,1,2").hudDisplayMode());
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSettingsArgument.parse("false,true"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSettingsArgument.parse("1,2"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSettingsArgument.parse("1,1,1,3"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSettingsArgument.parse("1,1,1,1x0"));
    }

    @Test
    void workspaceCreateArgumentRoundTripsWholeAndBoundedScopes() {
        var whole = new WorkspaceCreateArgument("Whole world", Optional.empty());
        var bounded = new WorkspaceCreateArgument(
                "Castle", Optional.of(
                        new io.github.lumi.domain.model.BlockBox(6, 5, 4, 3, 2, 1)));

        assertEquals(whole, WorkspaceCreateArgument.parse(whole.encode()));
        assertEquals(bounded, WorkspaceCreateArgument.parse(bounded.encode()));
    }

    @Test
    void versionTagsArgumentRoundTripsAndRejectsAmbiguousInput() {
        var argument = new VersionTagsArgument(
                id('a'), VersionTags.parse("#Roof, castle"));

        assertEquals(argument, VersionTagsArgument.parse(argument.encode()));
        assertEquals(
                new VersionTagsArgument(id('a'), VersionTags.empty()),
                VersionTagsArgument.parse(id('a').hex() + "\n"));
        assertThrows(IllegalArgumentException.class,
                () -> VersionTagsArgument.parse(id('a').hex()));
        assertThrows(IllegalArgumentException.class,
                () -> VersionTagsArgument.parse(id('a').hex() + "\nroof\ncastle"));
    }

    @Test
    void dimensionHistoryBrowseScopeRoundTrips() {
        var request = new HistoryPageRequestPayload(
                UUID.randomUUID(), "modded:moon",
                HistoryPageRequestPayload.ACTIVE_WORKSPACE,
                HistoryPageRequestPayload.ACTIVE_BRANCH,
                Optional.empty(), 0, 32, "tower");

        assertTrue(request.browsesDimension());
        assertEquals(request, roundTrip(HistoryPageRequestPayload.CODEC, request));
    }

    @Test
    void versionRenameArgumentRoundTripsAndRejectsAmbiguousInput() {
        var argument = new VersionRenameArgument(
                id('a'), new VersionDisplayName("Clock tower"));

        assertEquals(argument, VersionRenameArgument.parse(argument.encode()));
        assertThrows(IllegalArgumentException.class,
                () -> VersionRenameArgument.parse(id('a').hex()));
        assertThrows(IllegalArgumentException.class,
                () -> VersionRenameArgument.parse(id('a').hex() + "\nA\nB"));
    }

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
        var zoneCreate = new ZoneCreateArgument("Clock");
        HistoryCommandPayload createZone = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_CREATE,
                zoneCreate.encode(), id('1'), 42);
        assertEquals(createZone, roundTrip(HistoryCommandPayload.CODEC, createZone));
        var zoneCells = new ZoneCellsArgument(
                false, new io.github.lumi.domain.model.BlockBox(1, 2, 3, 4, 5, 6));
        HistoryCommandPayload editZone = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_CELLS,
                zoneCells.encode(), id('1'), 42);
        assertEquals(editZone, roundTrip(HistoryCommandPayload.CODEC, editZone));
        var zoneDelete = new ZoneDeleteArgument(UUID.randomUUID(), 7);
        HistoryCommandPayload deleteZone = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_DELETE,
                zoneDelete.encode(), id('1'), 42);
        assertEquals(deleteZone, roundTrip(
                HistoryCommandPayload.CODEC, deleteZone));
        ZoneOverlayPayload overlay = new ZoneOverlayPayload(
                UUID.randomUUID(), "minecraft:overworld", new UUID(0, 7),
                0, true, Optional.of(new ZoneOverlayPayload.ZoneBatch(
                        new UUID(0, 8), "Clock", 0xff336699, 3, true, true,
                        java.util.List.of(new io.github.lumi.domain.model.ZoneShellFace(
                                io.github.lumi.domain.model.ZoneShellFace.Side.UP,
                                16, 0, 16, 0, 16)))),
                "");
        assertEquals(overlay, roundTrip(ZoneOverlayPayload.CODEC, overlay));
        HistoryCommandPayload overlayRequest = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_OVERLAY,
                new ZoneOverlayArgument(
                        ZoneOverlayArgument.Mode.ALL).encode(),
                id('1'), 42);
        assertEquals(overlayRequest, roundTrip(
                HistoryCommandPayload.CODEC, overlayRequest));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.ZONE_ENTER,
                HistoryCommandPayload.Kind.ZONE_LEAVE)) {
            HistoryCommandPayload zoneActor = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, UUID.randomUUID().toString(), id('1'), 42);
            assertEquals(zoneActor, roundTrip(HistoryCommandPayload.CODEC, zoneActor));
        }
        UUID zoneId = UUID.randomUUID();
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.ZONE_SAVE,
                HistoryCommandPayload.Kind.ZONE_AMEND)) {
            HistoryCommandPayload zoneSave = new HistoryCommandPayload(
                    UUID.randomUUID(), kind,
                    new ZoneSaveArgument(
                            zoneId, 7, "Clock works",
                            VersionTags.parse("redstone")).encode(),
                    id('1'), 42);
            assertEquals(zoneSave, roundTrip(HistoryCommandPayload.CODEC, zoneSave));
        }
        HistoryCommandPayload zoneRestore = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_RESTORE,
                new ZoneRestoreArgument(zoneId, 7, id('2')).encode(), id('1'), 42);
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
        HistoryCommandPayload restoreDeletedVersion = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_DELETED_VERSION,
                id('3').hex(), id('2'), 42);
        assertEquals(restoreDeletedVersion,
                roundTrip(HistoryCommandPayload.CODEC, restoreDeletedVersion));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.PACKAGE_EXPORT,
                HistoryCommandPayload.Kind.PACKAGE_INSPECT)) {
            HistoryCommandPayload packageCommand = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, "clock-v2", id('1'), 42);
            assertEquals(packageCommand,
                    roundTrip(HistoryCommandPayload.CODEC, packageCommand));
        }
        HistoryCommandPayload importPackage = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.PACKAGE_IMPORT,
                UUID.randomUUID().toString(), id('1'), 42);
        assertEquals(importPackage,
                roundTrip(HistoryCommandPayload.CODEC, importPackage));
        OperationCancelPayload cancel = new OperationCancelPayload(
                UUID.randomUUID(), UUID.randomUUID());
        assertEquals(cancel, roundTrip(OperationCancelPayload.CODEC, cancel));
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.UNDO,
                HistoryCommandPayload.Kind.REDO,
                HistoryCommandPayload.Kind.RECOVER_RESUME,
                HistoryCommandPayload.Kind.RECOVER_RETURN,
                HistoryCommandPayload.Kind.SNAPSHOT_REFRESH,
                HistoryCommandPayload.Kind.CLEANUP_INSPECT,
                HistoryCommandPayload.Kind.CLEANUP_APPLY)) {
            HistoryCommandPayload live = new HistoryCommandPayload(
                    UUID.randomUUID(), kind, "", id('2'), 43);
            assertEquals(live, roundTrip(HistoryCommandPayload.CODEC, live));
        }
        HistoryCommandPayload rollback = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.QUICK_ROLLBACK,
                new QuickRollbackArgument().encode(),
                id('2'), 43);
        assertEquals(rollback, roundTrip(HistoryCommandPayload.CODEC, rollback));
        HistoryCommandPayload switchBranch = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_SWITCH,
                "workspace/lab/idea", id('2'), 43);
        assertEquals(switchBranch, roundTrip(HistoryCommandPayload.CODEC, switchBranch));
        HistoryCommandPayload createBranch = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_CREATE,
                "idea", id('2'), 43);
        assertEquals(createBranch, roundTrip(HistoryCommandPayload.CODEC, createBranch));
        HistoryCommandPayload createBranchAt = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_CREATE_AT,
                new BranchCreateArgument(
                        new io.github.lumi.domain.model.BranchName("idea-from-save"),
                        id('1')).encode(), id('2'), 43);
        assertEquals(createBranchAt,
                roundTrip(HistoryCommandPayload.CODEC, createBranchAt));
        HistoryCommandPayload deleteBranch = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.BRANCH_DELETE,
                "workspace/lab/idea", id('2'), 43);
        assertEquals(deleteBranch, roundTrip(HistoryCommandPayload.CODEC, deleteBranch));
        var boundedWorkspace = new WorkspaceCreateArgument(
                "Castle", Optional.of(
                        new io.github.lumi.domain.model.BlockBox(1, 2, 3, 4, 5, 6)));
        HistoryCommandPayload createWorkspace = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.WORKSPACE_CREATE,
                boundedWorkspace.encode(), id('2'), 43);
        assertEquals(createWorkspace,
                roundTrip(HistoryCommandPayload.CODEC, createWorkspace));
        HistoryCommandPayload switchWorkspace = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.WORKSPACE_SWITCH,
                UUID.randomUUID().toString(), id('2'), 43);
        assertEquals(switchWorkspace,
                roundTrip(HistoryCommandPayload.CODEC, switchWorkspace));
        HistoryCommandPayload workspaceSettings = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.WORKSPACE_SETTINGS,
                new WorkspaceSettingsArgument(
                        new WorkspaceSettings(false, true)).encode(), id('2'), 43);
        assertEquals(workspaceSettings,
                roundTrip(HistoryCommandPayload.CODEC, workspaceSettings));
        HistoryCommandPayload updateTags = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.UPDATE_VERSION_TAGS,
                new VersionTagsArgument(
                        id('3'), VersionTags.parse("roof, castle")).encode(),
                id('2'), 43);
        assertEquals(updateTags, roundTrip(HistoryCommandPayload.CODEC, updateTags));
        HistoryCommandPayload renameVersion = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RENAME_VERSION,
                new VersionRenameArgument(
                        id('3'), new VersionDisplayName("Clock tower")).encode(),
                id('2'), 43);
        assertEquals(renameVersion,
                roundTrip(HistoryCommandPayload.CODEC, renameVersion));
        HistoryCommandPayload restoreWithoutEntities = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_NO_ENTITIES,
                id('3').hex(), id('2'), 43);
        assertEquals(restoreWithoutEntities,
                roundTrip(HistoryCommandPayload.CODEC, restoreWithoutEntities));
        HistoryCommandPayload prewarmRestore = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_PREWARM,
                id('3').hex(), id('1'), 42);
        assertEquals(prewarmRestore,
                roundTrip(HistoryCommandPayload.CODEC, prewarmRestore));
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
        HistoryCommandPayload zoneCompareCommand = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.ZONE_COMPARE,
                new ZoneCompareArgument(UUID.randomUUID(), id('1'), id('2')).encode(),
                id('2'), 43);
        assertEquals(zoneCompareCommand,
                roundTrip(HistoryCommandPayload.CODEC, zoneCompareCommand));
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
                request, HistoryCommandPayload.Kind.RESTORE_DELETED_VERSION,
                "not-a-commit", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.SAVE, "Save", id('1'), -1));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.UNDO, "unexpected", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.BRANCH_CREATE, " ", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.BRANCH_DELETE, " ", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.WORKSPACE_CREATE, "bad", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.WORKSPACE_SWITCH,
                "not-a-workspace", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.WORKSPACE_SETTINGS,
                "true,false", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.COMPARE_CANCEL, "not-a-uuid", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.PACKAGE_EXPORT,
                "../outside", id('1'), 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryCommandPayload(
                request, HistoryCommandPayload.Kind.PACKAGE_IMPORT,
                "not-a-token", id('1'), 0));
    }

    @Test
    void snapshotAndTerminalEventRoundTrip() {
        HistorySnapshotPayload snapshot = new HistorySnapshotPayload(
                "minecraft:overworld", id('a'), 7, 3, 11,
                java.util.List.of(
                        new HistorySnapshotPayload.PendingBlock(1, 2, 3),
                        new HistorySnapshotPayload.PendingBlock(-4, -5, -6)),
                Optional.of(new BlockBox(-16, -32, -48, 31, 47, 63)),
                true, true,
                new UUID(0, 7), "Redstone lab", "workspace/lab/main",
                java.util.List.of(
                        new HistorySnapshotPayload.WorkspaceView(
                                new UUID(0, 7), "Redstone lab", true,
                                true, true, false, false,
                                HudDisplayMode.NONE, true),
                        new HistorySnapshotPayload.WorkspaceView(
                                new UUID(0, 9), "Whole world", false,
                                false, false, true),
                        new HistorySnapshotPayload.WorkspaceView(
                                new UUID(0, 10), "Bossbar", false,
                                false, false, true, true,
                                HudDisplayMode.BOSSBAR, false)),
                java.util.List.of(new HistorySnapshotPayload.Version(
                        id('a'), "Clock works", "Builder", 1234,
                        CommitKind.MANUAL, VersionTags.parse("redstone, stable"),
                        java.util.List.of(id('9')),
                        new CommitStatistics(4, 2, 8192, 3),
                        Optional.of(new UUID(0, 8)))),
                java.util.List.of(
                        new HistorySnapshotPayload.Branch("main", id('a'), true),
                        new HistorySnapshotPayload.Branch("idea", id('b'), false)),
                java.util.List.of(new HistorySnapshotPayload.ZoneView(
                        new UUID(0, 8), "Clock", 0xff336699, 4, 2, true,
                        java.util.List.of(new HistorySnapshotPayload.Version(
                                id('b'), "Clock v1", "Builder", 1235,
                                CommitKind.ZONE, VersionTags.parse("clock"))))),
                java.util.List.of(new HistorySnapshotPayload.Version(
                        id('c'), "Old clock", "Builder", 1200,
                        CommitKind.MANUAL, VersionTags.parse("archived"))));
        OperationEventPayload event = new OperationEventPayload(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "minecraft:overworld", OperationEventPayload.State.SUCCEEDED,
                "Saved", id('b'), 8, Optional.empty(), -1, Optional.empty(),
                Optional.of(new BlockBox(-16, 0, -32, 31, 47, 15)),
                Optional.of(RestoreStatisticsPayload.from(
                        new RestoreApplyStatistics(
                                7, 5, java.util.Map.of(), 9, 4096, 3,
                                2, 4, 8192, 10, 11, 12, 13, 14, 15, 16, 17,
                                18, 19, 20, 21, 22, 23, 24))));

        assertEquals(snapshot, roundTrip(HistorySnapshotPayload.CODEC, snapshot));
        assertEquals(event, roundTrip(OperationEventPayload.CODEC, event));
        assertEquals(new BlockBox(-16, 0, -32, 31, 47, 15),
                event.previewBounds().orElseThrow());
        assertEquals(4096,
                event.restoreStatistics().orElseThrow().changedBlocks());
        assertEquals(22,
                event.restoreStatistics().orElseThrow().chunkRegions());
        OperationEventPayload progress = new OperationEventPayload(
                UUID.randomUUID(), "minecraft:overworld",
                OperationEventPayload.State.PROGRESS, "Restore: applying", id('b'), 8,
                Optional.of(UUID.randomUUID()), 0,
                Optional.of(new OperationProgress("Restore: applying", 4, 10)));
        assertEquals(progress, roundTrip(OperationEventPayload.CODEC, progress));
        PackageInspectionPayload inspection = new PackageInspectionPayload(
                UUID.randomUUID(), "minecraft:overworld",
                new PackageName("clock-v2"), id('d'),
                "Working clock", "Builder", 123_456, 9);
        assertEquals(inspection,
                roundTrip(PackageInspectionPayload.CODEC, inspection));
        CleanupResultPayload cleanup = new CleanupResultPayload(
                UUID.randomUUID(), false, 2, 7, "");
        assertEquals(cleanup, roundTrip(CleanupResultPayload.CODEC, cleanup));
        SurvivalSettingsPayload survival = new SurvivalSettingsPayload(
                UUID.randomUUID(), true, true);
        assertEquals(survival,
                roundTrip(SurvivalSettingsPayload.CODEC, survival));
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
