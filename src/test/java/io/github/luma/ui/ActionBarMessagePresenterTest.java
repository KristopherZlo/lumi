package io.github.luma.ui;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionBarMessagePresenterTest {

    private static final Instant NOW = Instant.parse("2026-04-30T10:00:00Z");

    @Test
    void progressBarUsesPlainBuilderFacingAscii() {
        String bar = ActionBarMessagePresenter.asciiProgressBar(50);

        Assertions.assertEquals("[#####.....]", bar);
        Assertions.assertFalse(bar.contains("="));
        Assertions.assertFalse(bar.contains("-"));
    }

    @Test
    void progressBarOnlyShowsForLargeActiveWork() {
        Assertions.assertTrue(ActionBarMessagePresenter.shouldShowProgressBar(
                snapshot(OperationStage.APPLYING, 64, 128)
        ));
        Assertions.assertFalse(ActionBarMessagePresenter.shouldShowProgressBar(
                snapshot(OperationStage.APPLYING, 16, 32)
        ));
        Assertions.assertFalse(ActionBarMessagePresenter.shouldShowProgressBar(
                snapshot(OperationStage.FINALIZING, 128, 128)
        ));
        Assertions.assertFalse(ActionBarMessagePresenter.shouldShowProgressBar(
                snapshot(OperationStage.COMPLETED, 128, 128)
        ));
    }

    private static OperationSnapshot snapshot(OperationStage stage, int completed, int total) {
        return new OperationSnapshot(
                new OperationHandle("op", "project", "restore-version", NOW, false),
                stage,
                new OperationProgress(completed, total, "blocks"),
                "",
                NOW
        );
    }
}
