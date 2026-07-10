package io.github.luma.ui.overlay;

import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;

/**
 * Client GameTest smoke coverage for overlays that need a live renderer.
 */
public final class LumiOverlayClientSmoke {

    private static final String ACTOR = "Lumi overlay smoke";
    private final ProjectService projectService = new ProjectService();

    public void run(ClientGameTestContext context, TestSingleplayerContext singleplayer) throws Exception {
        BlockPos origin = context.computeOnClient(client -> {
            if (client.player == null || client.level == null) {
                throw new AssertionError("Minecraft client world is not ready for overlay smoke tests");
            }
            return client.player.blockPosition().offset(4, 2, 4);
        });
        RecentFixture fixture = null;
        try {
            this.runCompareSmoke(context, origin);
            this.runPendingSmoke(context, origin);
            fixture = this.prepareRecentFixture(singleplayer, origin);
            this.runRecentSmoke(context, fixture);
        } finally {
            this.clearOverlayState(context, fixture);
        }
    }

    private void runCompareSmoke(ClientGameTestContext context, BlockPos origin) throws Exception {
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            RecentChangesOverlayRenderer.clear();
            CompareOverlayRenderer.show(
                    "lumi-overlay-smoke",
                    "v0001",
                    "v0002",
                    this.compareEntries(origin, 2, 2, 2),
                    true
            );
            this.assertCompareMesh("small compare", 1, 1);
        });
        this.waitForRenderedCompareFrames(context, "small compare");
        context.takeScreenshot("lumi-compare-overlay-small");
        context.runOnClient(client -> CompareOverlayRenderer.clear());

        CompareOverlayRenderer.PreparedOverlay largeCompare = CompareOverlayRenderer.prepare(
                "lumi-overlay-smoke",
                "v0001",
                "v0002-large",
                this.compareEntries(origin, 80, 64, 64),
                true,
                true
        );
        context.runOnClient(client -> {
            CompareOverlayRenderer.activatePrepared(largeCompare);
            this.assertCompareMesh("large compare", 1, 1);
            this.assertCompareMeshNearCamera(client, "large compare");
        });
        context.runOnClient(client -> CompareOverlayRenderer.clear());
    }

    private void runPendingSmoke(ClientGameTestContext context, BlockPos origin) throws Exception {
        PendingChangesOverlayRenderer.PreparedOverlay pending = PendingChangesOverlayRenderer.prepare(
                new PendingChangesOverlaySnapshot(
                        "lumi-overlay-smoke",
                        100_064L,
                        this.pendingChanges(origin),
                        0
                ),
                true
        );
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            RecentChangesOverlayRenderer.clear();
            PendingChangesOverlayRenderer.clear();
            PendingChangesOverlayRenderer.activate(pending);
            this.assertPendingMesh("pending cumulative", 1, 1);
            this.assertPendingMeshNearCamera(client, "pending cumulative");
        });
        context.runOnClient(client -> PendingChangesOverlayRenderer.clear());
    }

    private RecentFixture prepareRecentFixture(TestSingleplayerContext singleplayer, BlockPos origin) throws Exception {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            server.getPlayerList().getPlayers().stream()
                    .findFirst()
                    .ifPresent(player -> server.getPlayerList().op(
                            new NameAndId(player.getGameProfile()),
                            Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                            Optional.of(false)
                    ));
            var project = this.projectService.ensureWorldProject(level, ACTOR);
            String dimensionId = level.dimension().identifier().toString();
            String projectId = project.id().toString();
            UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
            historyManager.clearProject(projectId);
            historyManager.recordAction(
                    projectId,
                    dimensionId,
                    "lumi-overlay-smoke-small",
                    ACTOR,
                    this.storedChanges(origin, 2, 2, 2),
                    List.of(),
                    Instant.now()
            );
            return new RecentFixture(projectId, dimensionId, origin);
        });
    }

    private void runRecentSmoke(ClientGameTestContext context, RecentFixture fixture) throws Exception {
        UndoRedoAction smallAction = this.undoRedoAction(
                fixture.projectId(),
                fixture.dimensionId(),
                "lumi-overlay-smoke-small",
                this.storedChanges(fixture.origin(), 2, 2, 2)
        );
        RecentChangesOverlayRenderer.PreparedOverlay smallPrepared = RecentChangesOverlayRenderer.prepare(
                fixture.projectId(),
                List.of(smallAction),
                false,
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                -1L
        );
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            RecentChangesOverlayRenderer.clear();
            RecentChangesOverlayRenderer.activate(smallPrepared);
            this.assertRecentMesh("small recent", 1, 1);
            this.assertRecentMeshNearCamera(client, "small recent");
        });
        try {
            UndoRedoAction largeAction = this.undoRedoAction(
                    fixture.projectId(),
                    fixture.dimensionId(),
                    "lumi-overlay-smoke-large",
                    this.storedChanges(fixture.origin(), 64, 40, 40)
            );
            RecentChangesOverlayRenderer.PreparedOverlay largePrepared = RecentChangesOverlayRenderer.prepare(
                    fixture.projectId(),
                    List.of(largeAction),
                    false,
                    RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                    -1L
            );
            context.runOnClient(client -> {
                RecentChangesOverlayRenderer.activate(largePrepared);
                this.assertRecentMesh("large recent", 1, 1);
                this.assertRecentMeshNearCamera(client, "large recent");
            });
        } finally {
            context.runOnClient(client -> {
                RecentChangesOverlayRenderer.clear();
            });
        }
    }

    private void clearOverlayState(ClientGameTestContext context, RecentFixture fixture) throws Exception {
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            PendingChangesOverlayRenderer.clear();
            RecentChangesOverlayRenderer.clear();
            KeyMapping actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
            if (actionKey != null) {
                actionKey.setDown(false);
            }
        });
        if (fixture != null) {
            UndoRedoHistoryManager.getInstance().clearProject(fixture.projectId());
        }
    }

    private void assertCompareMesh(String label, int minSections, int minPrimitives) {
        if (!CompareOverlayRenderer.visible()) {
            throw new AssertionError(label + " overlay is not visible");
        }
        if (CompareOverlayRenderer.meshSectionCountForTest() < minSections) {
            throw new AssertionError(label + " mesh has no cached sections");
        }
        if (CompareOverlayRenderer.meshPrimitiveCountForTest() < minPrimitives) {
            throw new AssertionError(label + " mesh has no primitives");
        }
    }

    private void assertRecentMesh(String label, int minSections, int minPrimitives) {
        if (!RecentChangesOverlayRenderer.visible()) {
            throw new AssertionError(label + " overlay is not visible");
        }
        if (RecentChangesOverlayRenderer.meshSectionCountForTest() < minSections) {
            throw new AssertionError(label + " mesh has no cached sections");
        }
        if (RecentChangesOverlayRenderer.meshPrimitiveCountForTest() < minPrimitives) {
            throw new AssertionError(label + " mesh has no primitives");
        }
    }

    private void assertPendingMesh(String label, int minSections, int minPrimitives) {
        if (!PendingChangesOverlayRenderer.visible()) {
            throw new AssertionError(label + " overlay is not visible");
        }
        if (PendingChangesOverlayRenderer.meshSectionCountForTest() < minSections) {
            throw new AssertionError(label + " mesh has no cached sections");
        }
        if (PendingChangesOverlayRenderer.meshPrimitiveCountForTest() < minPrimitives) {
            throw new AssertionError(label + " mesh has no primitives");
        }
    }

    private void waitForRenderedCompareFrames(ClientGameTestContext context, String label) throws Exception {
        for (int i = 0; i < 3; i++) {
            context.waitTick();
            context.runOnClient(client -> {
                this.assertCompareMesh(label, 1, 1);
                this.assertCompareMeshNearCamera(client, label);
            });
        }
    }

    private void assertCompareMeshNearCamera(net.minecraft.client.Minecraft client, String label) {
        var camera = client.gameRenderer.getMainCamera().position();
        if (CompareOverlayRenderer.visibleMeshSectionCountForTest(camera.x, camera.y, camera.z, 8) < 1) {
            throw new AssertionError(label + " overlay has no visible mesh sections near the camera");
        }
    }

    private void assertRecentMeshNearCamera(net.minecraft.client.Minecraft client, String label) {
        var camera = client.gameRenderer.getMainCamera().position();
        if (RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(camera.x, camera.y, camera.z, 8) < 1) {
            throw new AssertionError(label + " overlay has no visible mesh sections near the camera");
        }
    }

    private void assertPendingMeshNearCamera(net.minecraft.client.Minecraft client, String label) {
        var camera = client.gameRenderer.getMainCamera().position();
        if (PendingChangesOverlayRenderer.visibleMeshSectionCountForTest(camera.x, camera.y, camera.z, 8) < 1) {
            throw new AssertionError(label + " overlay has no visible mesh sections near the camera");
        }
    }

    private List<DiffBlockEntry> compareEntries(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        List<DiffBlockEntry> entries = new ArrayList<>(sizeX * sizeY * sizeZ);
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    entries.add(new DiffBlockEntry(
                            BlockPoint.from(origin.offset(x, y, z)),
                            "minecraft:air",
                            "minecraft:glass",
                            ChangeType.ADDED
                    ));
                }
            }
        }
        return entries;
    }

    private List<StoredBlockChange> storedChanges(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        List<StoredBlockChange> changes = new ArrayList<>(sizeX * sizeY * sizeZ);
        StatePayload oldValue = new StatePayload(this.state("minecraft:air"), null);
        StatePayload newValue = new StatePayload(this.state("minecraft:glass"), null);
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    changes.add(new StoredBlockChange(
                            BlockPoint.from(origin.offset(x, y, z)),
                            oldValue,
                            newValue
                    ));
                }
            }
        }
        return changes;
    }

    private List<StoredBlockChange> pendingChanges(BlockPos origin) {
        List<StoredBlockChange> changes = new ArrayList<>(100_064);
        changes.addAll(this.storedChanges(origin, 100, 40, 25));
        changes.addAll(this.storedChanges(origin.offset(112, 0, 0), 8, 4, 2));
        return changes;
    }

    private UndoRedoAction undoRedoAction(
            String projectId,
            String dimensionId,
            String actionId,
            List<StoredBlockChange> changes
    ) {
        Instant changedAt = Instant.now();
        UndoRedoAction action = new UndoRedoAction(actionId, ACTOR, projectId, dimensionId, changedAt, changedAt);
        for (StoredBlockChange change : changes) {
            action.recordChange(change, changedAt);
        }
        return action;
    }

    private CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private record RecentFixture(String projectId, String dimensionId, BlockPos origin) {
    }
}
