package io.github.luma.gbreak.client.ui.controller;

import io.github.luma.gbreak.client.ui.state.FakeCommitEntry;
import java.util.List;

public final class FakeLumiProjectCatalog {

    public List<String> defaultVariants() {
        return List.of("mainline", "corrupt-preview");
    }

    public List<FakeCommitEntry> defaultCommits() {
        return List.of(
                new FakeCommitEntry(
                        "a3f91d2",
                        "Before corrupted hallway rollback",
                        "Lumi Dev",
                        "2026-04-24 22:14",
                        "corrupt-preview",
                        "Restore checkpoint",
                        148,
                        6,
                        true
                ),
                new FakeCommitEntry(
                        "7be42aa",
                        "Glass tunnel fallback pass",
                        "Lumi Dev",
                        "2026-04-24 21:46",
                        "mainline",
                        "Save",
                        63,
                        3,
                        false
                )
        );
    }
}
