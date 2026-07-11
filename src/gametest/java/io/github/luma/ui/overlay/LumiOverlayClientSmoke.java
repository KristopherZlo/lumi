package io.github.luma.ui.overlay;

import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Client GameTest smoke coverage for durable compare and pending overlays. */
public final class LumiOverlayClientSmoke {

    public void run(ClientGameTestContext context, TestSingleplayerContext ignoredSingleplayer) throws Exception {
        BlockPos origin = context.computeOnClient(client -> {
            if (client.player == null || client.level == null) {
                throw new AssertionError("Minecraft client world is not ready for overlay smoke tests");
            }
            return client.player.blockPosition().offset(4, 2, 4);
        });
        try {
            this.runCompareSmoke(context, origin);
            this.runPendingSmoke(context, origin);
        } finally {
            this.clearOverlayState(context);
        }
    }

    private void runCompareSmoke(ClientGameTestContext context, BlockPos origin) throws Exception {
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            CompareOverlayRenderer.show(
                    "lumi-overlay-smoke",
                    "v0001",
                    "v0002",
                    this.compareEntries(origin, 2, 2, 2),
                    true
            );
            this.assertCompareMesh("small compare");
        });
        for (int i = 0; i < 3; i++) {
            context.waitTick();
            context.runOnClient(client -> {
                this.assertCompareMesh("small compare");
                this.assertCompareMeshNearCamera(client, "small compare");
            });
        }
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
            this.assertCompareMesh("large compare");
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
            PendingChangesOverlayRenderer.clear();
            PendingChangesOverlayRenderer.activate(pending);
            if (!PendingChangesOverlayRenderer.visible()
                    || PendingChangesOverlayRenderer.meshSectionCountForTest() < 1
                    || PendingChangesOverlayRenderer.meshPrimitiveCountForTest() < 1) {
                throw new AssertionError("pending overlay has no visible mesh");
            }
            var camera = client.gameRenderer.getMainCamera().position();
            if (PendingChangesOverlayRenderer.visibleMeshSectionCountForTest(camera.x, camera.y, camera.z, 8) < 1) {
                throw new AssertionError("pending overlay has no visible mesh sections near the camera");
            }
        });
        context.runOnClient(client -> PendingChangesOverlayRenderer.clear());
    }

    private void clearOverlayState(ClientGameTestContext context) throws Exception {
        context.runOnClient(client -> {
            CompareOverlayRenderer.clear();
            PendingChangesOverlayRenderer.clear();
            KeyMapping actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
            if (actionKey != null) {
                actionKey.setDown(false);
            }
        });
    }

    private void assertCompareMesh(String label) {
        if (!CompareOverlayRenderer.visible()
                || CompareOverlayRenderer.meshSectionCountForTest() < 1
                || CompareOverlayRenderer.meshPrimitiveCountForTest() < 1) {
            throw new AssertionError(label + " overlay has no visible mesh");
        }
    }

    private void assertCompareMeshNearCamera(net.minecraft.client.Minecraft client, String label) {
        var camera = client.gameRenderer.getMainCamera().position();
        if (CompareOverlayRenderer.visibleMeshSectionCountForTest(camera.x, camera.y, camera.z, 8) < 1) {
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

    private List<StoredBlockChange> pendingChanges(BlockPos origin) {
        List<StoredBlockChange> changes = new ArrayList<>(100_064);
        changes.addAll(this.storedChanges(origin, 100, 40, 25));
        changes.addAll(this.storedChanges(origin.offset(112, 0, 0), 8, 4, 2));
        return changes;
    }

    private List<StoredBlockChange> storedChanges(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        List<StoredBlockChange> changes = new ArrayList<>(sizeX * sizeY * sizeZ);
        StatePayload oldValue = new StatePayload(this.state("minecraft:air"), null);
        StatePayload newValue = new StatePayload(this.state("minecraft:glass"), null);
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    changes.add(new StoredBlockChange(BlockPoint.from(origin.offset(x, y, z)), oldValue, newValue));
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
}
