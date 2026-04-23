package io.github.luma.gbreak.client.ui.state;

import java.util.List;

public record FakeLumiProjectViewState(
        String projectName,
        String dimensionName,
        String activeVariantName,
        String statusMessage,
        int pendingAdded,
        int pendingRemoved,
        int pendingChanged,
        List<String> variantNames,
        List<FakeCommitEntry> commits
) {
}
