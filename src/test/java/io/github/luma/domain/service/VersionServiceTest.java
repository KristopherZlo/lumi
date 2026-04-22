package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionServiceTest {

    @Test
    void mergeChangesBuildsParentToCurrentDiffForAmend() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(
                        change(1, "minecraft:stone", "minecraft:dirt"),
                        change(2, "minecraft:oak_planks", "minecraft:glass")
                ),
                List.of(
                        change(1, "minecraft:dirt", "minecraft:gold_block"),
                        change(2, "minecraft:glass", "minecraft:oak_planks")
                )
        );

        assertEquals(1, merged.size());
        assertEquals(new BlockPoint(1, 64, 1), merged.getFirst().pos());
        assertEquals("minecraft:stone", merged.getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", merged.getFirst().newValue().blockId());
    }

    @Test
    void mergeChangesDropsFullReverts() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(change(1, "minecraft:stone", "minecraft:dirt")),
                List.of(change(1, "minecraft:dirt", "minecraft:stone"))
        );

        assertTrue(merged.isEmpty());
    }

    @Test
    void versionsSinceSnapshotTreatsWorldRootAsAnchor() {
        VersionService service = new VersionService();
        List<ProjectVersion> versions = List.of(
                version("v0001", "", "", VersionKind.WORLD_ROOT),
                version("v0002", "v0001", "", VersionKind.MANUAL),
                version("v0003", "v0002", "", VersionKind.MANUAL)
        );

        assertEquals(2, service.versionsSinceSnapshot(versions, "v0003"));
    }

    @Test
    void chunkBoundsWrapExactTouchedChunkSpan() {
        Bounds3i bounds = VersionService.chunkBounds(
                List.of(new ChunkPoint(10, 20), new ChunkPoint(11, 22)),
                -64,
                319
        );

        assertEquals(new BlockPoint(160, -64, 320), bounds.min());
        assertEquals(new BlockPoint(191, 319, 367), bounds.max());
    }

    private static StoredBlockChange change(int x, String leftBlockId, String rightBlockId) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, x),
                payload(leftBlockId),
                payload(rightBlockId)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }

    private static ProjectVersion version(String id, String parentId, String snapshotId, VersionKind versionKind) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                snapshotId,
                List.of(),
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-04-21T00:00:00Z")
        );
    }
}
