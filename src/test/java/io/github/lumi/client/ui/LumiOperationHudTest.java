package io.github.lumi.client.ui;
// Checks the shared source before platform API substitutions.
//~ !native-api
//~ !native-types

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.client.state.ClientNotificationStore;
import io.github.lumi.client.state.HighlightPreparationProgress;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistoryCommandPayload;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.minecraft.operation.OperationProgress;
import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LumiOperationHudTest {
    @Test
    void usesRealSaveLabelsAndKeepsFailuresOutOfTheSavedState() {
        var history = new ClientHistoryStore();
        var hud = new LumiOperationHud(history,
                new ClientPendingStatisticsStore(),
                new ClientNotificationStore(),
                () -> HighlightPreparationProgress.NONE);
        var request = Optional.of(new ClientHistoryStore.Request(
                HistoryCommandPayload.Kind.SAVE, "Courtyard arch"));
        var saved = hud.feedback(new ClientNotificationStore.Notification(
                Component.literal("Operation completed"), 0xff00ff00,
                5_000_000_000L, request, false, 1), List.of());
        assertTrue(saved.saved());
        assertEquals("Courtyard arch", saved.detail());
        assertFalse(saved.busy());
        var failed = hud.feedback(new ClientNotificationStore.Notification(
                Component.literal("Cannot write history"), 0xffff0000,
                5_000_000_000L, request, true, 1), List.of());
        assertFalse(failed.saved());
        assertTrue(failed.error());
        assertEquals("Cannot write history", failed.detail());
        var warning = hud.feedback(new ClientNotificationStore.Notification(
                Component.literal("Target could not be applied; returned safely"), 0xffffaa00,
                5_000_000_000L, Optional.empty(), false, 1), List.of());
        assertEquals(0xffffaa00, warning.detailColor());
        for (var message : List.of(Component.translatable("luma.selection.resized"), Component.literal("Updated"))) {
            var first = hud.feedback(new ClientNotificationStore.Notification(
                    message, 0xffffffff, 5_000_000_000L, Optional.empty(), false, 1), List.of());
            var repeated = hud.feedback(new ClientNotificationStore.Notification(
                    message.copy(), 0xffffffff, 6_000_000_000L, Optional.empty(), false, 1), List.of());
            assertEquals(first.identity(), repeated.identity());
            assertEquals(first.detail(), repeated.detail());
            assertNotEquals(first.identity(), warning.identity());
        }
        var nextSave = hud.feedback(new ClientNotificationStore.Notification(
                Component.literal("Operation completed"), 0xff00ff00,
                6_000_000_000L, request, false, 1), List.of());
        assertNotEquals(saved.identity(), nextSave.identity());
        var nextFailure = hud.feedback(new ClientNotificationStore.Notification(
                Component.literal("Cannot write history"), 0xffff0000,
                6_000_000_000L, request, true, 1), List.of());
        assertNotEquals(failed.identity(), nextFailure.identity());
    }

    @Test
    void preservesExactAndUnknownProgressWithoutAnElapsedTimeEstimate() {
        var history = new ClientHistoryStore();
        var hud = new LumiOperationHud(history,
                new ClientPendingStatisticsStore(),
                new ClientNotificationStore(),
                () -> HighlightPreparationProgress.NONE);
        var request = UUID.randomUUID();
        var head = new CommitId(new ObjectId("0".repeat(64)));
        for (long total : new long[]{0, 10}) {
            var event = new OperationEventPayload(request, "minecraft:overworld",
                    OperationEventPayload.State.PROGRESS, "Preparing", head, 0,
                    Optional.of(UUID.randomUUID()), 0,
                    Optional.of(new OperationProgress("Reading saved state", total == 0 ? 0 : 3, total)));
            var content = hud.operation(event, List.of());
            assertEquals(total != 0, content.progress().isPresent());
            if (total != 0) assertEquals(.3, content.progress().getAsDouble());
            assertTrue(content.busy());
            assertEquals("Reading saved state", content.detail());
        }
    }

    @Test
    void boundsDetailedFeedbackAndFitsTheProgressTrackInsideOnePanel() {
        assertEquals(24, LumiHudPanel.contentHeight(true, 0, false, 0));
        assertEquals(42, LumiHudPanel.contentHeight(false, 1, false, 0));
        assertEquals(50, LumiHudPanel.contentHeight(false, 1, true, 0));
        int expanded = LumiHudPanel.contentHeight(true, 1, false, 3);
        assertEquals(8, expanded - (24 + 4 + 2 * 13 + 9));
        assertEquals(LumiHudPanel.contentHeight(false, 3, true, 3),
                LumiHudPanel.contentHeight(false, 500, true, 500));
        assertTrue(LumiHudPanel.contentHeight(false, 500, true, 500) < 120);
    }

    @Test
    void movesIndeterminateProgressBackAndForth() {
        assertEquals(0, LumiOperationHud.indeterminateOffset(0, 40));
        assertEquals(40, LumiOperationHud.indeterminateOffset(800, 40));
        assertEquals(0, LumiOperationHud.indeterminateOffset(1_600, 40));
    }

    @Test
    void honorsTheActiveWorkspaceHudSetting() throws Exception {
        String source = io.github.lumi.ProductionJavaSource.read(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOperationHud.java"));

        assertTrue(source.contains("history.state().hudDisplayMode() == HudDisplayMode.GUI"));
        assertTrue(source.contains("if (!usesGui()) return;"));
        assertTrue(source.contains("ScreenEvents.AFTER_INIT"));
        assertTrue(source.contains("ScreenEvents.afterRender(screen)")
                || source.contains("ScreenEvents.afterExtract(screen)"));
        assertTrue(source.contains("client.screen == current"));
        assertTrue(source.contains("notifications.visible()"));
        assertTrue(source.contains("ClientPlayConnectionEvents.DISCONNECT"));
        assertFalse(source.contains("mouseClicked("));
        assertTrue(source.contains("LumiHotkeys.actionModifierDown("));
        assertTrue(source.contains("result.workspace().total()"));
        assertTrue(source.contains("Integer.toString(snapshot.pendingKeys())"));
        assertFalse(source.contains("\"…\""));
        assertTrue(source.contains("panel.render(graphics, content, y)"));
        assertTrue(source.contains("!history.isNativeWorldAction(event.requestId())"));
        assertTrue(source.contains("\"key.lumi.quick_rollback\""));
        assertTrue(source.contains("\"key.lumi.open_dashboard\""));
        assertFalse(source.contains("\"Alt+R rollback\""));
    }
}
