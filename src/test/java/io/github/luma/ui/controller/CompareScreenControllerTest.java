package io.github.luma.ui.controller;

import io.github.luma.domain.model.VersionDiff;
import io.github.luma.ui.state.CompareLoadState;
import io.github.luma.ui.state.CompareViewState;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompareScreenControllerTest {

    @Test
    void emptyDiffReportsNothingToCompare() {
        CompareViewState state = new CompareViewState(
                List.of(),
                List.of(),
                "main",
                "v0001",
                "current",
                "v0001",
                "current",
                new VersionDiff("v0001", "current", List.of(), 0),
                List.of(),
                "luma.status.compare_ready",
                false,
                CompareLoadState.READY
        );

        assertEquals("luma.status.compare_no_changes", new CompareScreenController().showOverlay("Tower", state));
    }
}
