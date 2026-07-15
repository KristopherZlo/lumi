package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates the immutable empty root and main ref for a fresh dimension repository. */
public final class DimensionHistoryInitializer {
    private static final BranchName MAIN = new BranchName("main");
    private static final CommitAuthor SYSTEM =
            new CommitAuthor(new UUID(0, 0), "Lumi");

    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public DimensionHistoryInitializer(
            WorldObjectRepository objects,
            CommitRepository commits,
            BranchRefRepository refs) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public synchronized BranchRef initialize(UUID workspaceId) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Optional<BranchRef> existing = refs.read(MAIN);
        if (existing.isPresent()) {
            Commit root = commits.read(existing.orElseThrow().commit());
            objects.readDimension(root.tree());
            return existing.orElseThrow();
        }
        var tree = objects.write(new DimensionTree(Map.of()));
        var commit = commits.write(new Commit(
                tree, List.of(), SYSTEM, "Initial world", Instant.EPOCH,
                workspaceId, Optional.empty(), CommitKind.HIDDEN_SAFETY,
                new CommitStatistics(0, 0, 0, 0)));
        return refs.create(MAIN, commit);
    }
}
