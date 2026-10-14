package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;

/** One zone history page and the workspace branches that contain that zone. */
public record ZoneHistoryPage(
        HistoryPage page, List<BranchRef> branches) {
    public ZoneHistoryPage {
        Objects.requireNonNull(page, "page");
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
    }
}
