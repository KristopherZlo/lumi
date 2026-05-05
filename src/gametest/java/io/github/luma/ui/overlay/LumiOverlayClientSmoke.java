package io.github.luma.ui.overlay;

import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
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
    private static final int WAIT_TIMEOUT_TICKS = 20 * 30;

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

        context.runOnClient(client -> {
            CompareOverlayRenderer.show(
                    "lumi-overlay-smoke",
                    "v0001",
                    "v0002-large",
                    this.compareEntries(origin, 80, 64, 64),
                    true
            );
            this.assertCompareMesh("large compare", 1, 1);
            this.assertCompareMeshNearCamera(client, "large compare");
        });
        context.runOnClient(client -> CompareOverlayRenderer.clear());
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
        context.runOnClient(client -> {
            KeyMapping actionKey = this.actionKey();
            CompareOverlayRenderer.clear();
            RecentChangesOverlayRenderer.clear();
            actionKey.setDown(true);
        });
        try {
            this.waitForRecentMesh(context, "small recent", 1, 1);
            this.waitForRenderedRecentFrames(context, "small recent");
            context.takeScreenshot("lumi-recent-overlay-small");
            context.runOnClient(client -> {
                this.actionKey().setDown(false);
                RecentChangesOverlayRenderer.clear();
            });
            context.waitTick();

            UndoRedoHistoryManager.getInstance().clearProject(fixture.projectId());
            UndoRedoHistoryManager.getInstance().recordAction(
                    fixture.projectId(),
                    fixture.dimensionId(),
                    "lumi-overlay-smoke-large",
                    ACTOR,
                    this.storedChanges(fixture.origin(), 64, 40, 40),
                    List.of(),
                    Instant.now()
            );
            context.runOnClient(client -> this.actionKey().setDown(true));
            this.waitForRecentMesh(context, "large recent", 1, 1);
            context.runOnClient(client -> this.assertRecentMeshNearCamera(client, "large recent"));
            this.assertPinnedRecentOverlaySurvivesLiveEdits(context, fixture);
        } finally {
            context.runOnClient(client -> {
                this.actionKey().setDown(false);
                RecentChangesOverlayRenderer.clear();
            });
        }
    }

    private void assertPinnedRecentOverlaySurvivesLiveEdits(
            ClientGameTestContext context,
            RecentFixture fixture
    ) throws Exception {
        int largePrimitiveCount = context.computeOnClient(client ->
                RecentChangesOverlayRenderer.meshPrimitiveCountForTest());
        if (largePrimitiveCount < 1) {
            throw new AssertionError("large recent overlay did not expose mesh primitives before live edit pinning check");
        }

        UndoRedoHistoryManager.getInstance().recordAction(
                fixture.projectId(),
                fixture.dimensionId(),
                "lumi-overlay-smoke-live-edit",
                ACTOR,
                this.storedChanges(fixture.origin().offset(96, 0, 0), 5, 2, 2),
                List.of(),
                Instant.now()
        );

        for (int tick = 0; tick < 10; tick++) {
            context.waitTick();
            int currentPrimitiveCount = context.computeOnClient(client -> {
                if (!RecentChangesOverlayRenderer.visible()) {
                    throw new AssertionError("large recent overlay disappeared after a live edit while Alt was held");
                }
                return RecentChangesOverlayRenderer.meshPrimitiveCountForTest();
            });
            if (currentPrimitiveCount != largePrimitiveCount) {
                throw new AssertionError(
                        "large recent overlay was replaced after live edits while Alt was held: expected "
                                + largePrimitiveCount
                                + " primitives but saw "
                                + currentPrimitiveCount
                );
            }
        }
    }

    private void clearOverlayState(ClientGameTestContext context, RecentFixture fixture) throws Exception {
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
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

    private void waitForRecentMesh(
            ClientGameTestContext context,
            String label,
            int minSections,
            int minPrimitives
    ) throws Exception {
        for (int tick = 0; tick < WAIT_TIMEOUT_TICKS; tick++) {
            boolean ready = context.computeOnClient(client -> RecentChangesOverlayRenderer.visible()
                    && RecentChangesOverlayRenderer.meshSectionCountForTest() >= minSections
                    && RecentChangesOverlayRenderer.meshPrimitiveCountForTest() >= minPrimitives);
            if (ready) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(label + " overlay did not become visible");
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

    private void waitForRenderedRecentFrames(ClientGameTestContext context, String label) throws Exception {
        for (int i = 0; i < 3; i++) {
            context.waitTick();
            context.runOnClient(client -> {
                if (!RecentChangesOverlayRenderer.visible()) {
                    throw new AssertionError(label + " overlay disappeared during render smoke");
                }
                this.assertRecentMeshNearCamera(client, label);
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

    private KeyMapping actionKey() {
        KeyMapping actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        if (actionKey == null) {
            throw new AssertionError("Lumi action key binding was not registered");
        }
        return actionKey;
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

    private CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private record RecentFixture(String projectId, String dimensionId, BlockPos origin) {
    }
}
