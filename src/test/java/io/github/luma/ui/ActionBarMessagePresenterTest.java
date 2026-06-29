package io.github.luma.ui;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionBarMessagePresenterTest {

    private static final Instant NOW = Instant.parse("2026-04-30T10:00:00Z");

    @Test
    void actionBarDoesNotRenderTextProgressBar() {
        String message = ActionBarMessagePresenter.operation(snapshot(OperationStage.APPLYING, 64, 128)).getString();

        Assertions.assertTrue(message.contains("%"));
        Assertions.assertFalse(message.contains("#"));
    }

    @Test
    void actionBarPercentShowsForActiveWorkWithKnownTotal() {
        Assertions.assertTrue(ActionBarMessagePresenter.shouldShowOperationPercent(
                snapshot(OperationStage.APPLYING, 64, 128)
        ));
        Assertions.assertTrue(ActionBarMessagePresenter.shouldShowOperationPercent(
                snapshot(OperationStage.PRELOADING, 16, 32)
        ));
        Assertions.assertFalse(ActionBarMessagePresenter.shouldShowOperationPercent(
                snapshot(OperationStage.APPLYING, 0, 0)
        ));
        Assertions.assertFalse(ActionBarMessagePresenter.shouldShowOperationPercent(
                snapshot(OperationStage.COMPLETED, 128, 128)
        ));
    }

    @Test
    void selectionModeMessageColorsLabelAndModeSeparately() {
        Component message = ActionBarMessagePresenter.selection("luma.selection.mode_corners");

        Assertions.assertEquals("Lumi | Selection mode: corners", message.getString());
        Assertions.assertEquals(ChatFormatting.WHITE.getColor(), message.getSiblings().get(2).getStyle().getColor().getValue());
        Assertions.assertEquals(ChatFormatting.GOLD.getColor(), message.getSiblings().get(4).getStyle().getColor().getValue());
    }

    @Test
    void zoneRestoreUsesSpecificOperationLabel() {
        Component message = ActionBarMessagePresenter.operation(snapshot("zone-restore", OperationStage.APPLYING, 1, 2));
        var label = message.getSiblings().get(2).getContents();

        Assertions.assertInstanceOf(TranslatableContents.class, label);
        Assertions.assertEquals("luma.actionbar.operation.zone_restore", ((TranslatableContents) label).getKey());
    }

    private static OperationSnapshot snapshot(OperationStage stage, int completed, int total) {
        return snapshot("restore-version", stage, completed, total);
    }

    private static OperationSnapshot snapshot(String label, OperationStage stage, int completed, int total) {
        return new OperationSnapshot(
                new OperationHandle("op", "project", label, NOW, false),
                stage,
                new OperationProgress(completed, total, "blocks"),
                "",
                NOW
        );
    }
}
