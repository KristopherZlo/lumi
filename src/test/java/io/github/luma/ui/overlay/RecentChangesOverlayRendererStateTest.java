package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentChangesOverlayRendererStateTest {

    @AfterEach
    void tearDown() {
        RecentChangesOverlayRenderer.clear();
    }

    @Test
    void showAndClearToggleVisibility() {
        UndoRedoAction action = new UndoRedoAction(
                "action",
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        action.recordChange(new StoredBlockChange(
                new BlockPoint(10, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        ), Instant.parse("2026-04-23T08:00:01Z"));

        RecentChangesOverlayRenderer.show("project", List.of(action));

        assertTrue(RecentChangesOverlayRenderer.visible());

        RecentChangesOverlayRenderer.clear();

        assertFalse(RecentChangesOverlayRenderer.visible());
    }

    @Test
    void denseRecentActionStillExposesRenderableSurfaceBlocks() {
        UndoRedoAction action = new UndoRedoAction(
                "action",
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        for (StoredBlockChange change : denseCubeChanges()) {
            action.recordChange(change, Instant.parse("2026-04-23T08:00:01Z"));
        }

        RecentChangesOverlayRenderer.show("project", List.of(action));

        assertTrue(RecentChangesOverlayRenderer.visible());
        assertTrue(RecentChangesOverlayRenderer.visibleSurfaceEntryCountForTest(10.5D, 70.5D, 10.5D) > 0);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static List<StoredBlockChange> denseCubeChanges() {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = 0; z < 20; z++) {
                    changes.add(new StoredBlockChange(
                            new BlockPoint(x, y, z),
                            new StatePayload(state("minecraft:stone"), null),
                            new StatePayload(state("minecraft:glass"), null)
                    ));
                }
            }
        }
        return List.copyOf(changes);
    }
}
