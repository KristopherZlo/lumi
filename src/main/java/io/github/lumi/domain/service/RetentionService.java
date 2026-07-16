package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/** Applies ref retention without rewriting immutable commit history. */
public final class RetentionService {
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public RetentionService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public int pruneHiddenRefs(int keep) throws IOException {
        if (keep < 0) {
            throw new IllegalArgumentException("Retention count cannot be negative");
        }
        record HiddenRef(BranchRef ref, Instant timestamp) { }
        var hidden = new ArrayList<HiddenRef>();
        for (BranchRef ref : refs.list()) {
            if (!ref.name().value().startsWith("hidden/")) {
                continue;
            }
            var commit = commits.read(ref.commit());
            if (commit.kind() == CommitKind.HIDDEN_RETURN
                    || commit.kind() == CommitKind.HIDDEN_SAFETY) {
                hidden.add(new HiddenRef(ref, commit.timestamp()));
            }
        }
        hidden.sort(Comparator.comparing(HiddenRef::timestamp).reversed()
                .thenComparing(item -> item.ref().name().value()));
        for (int index = keep; index < hidden.size(); index++) {
            refs.delete(hidden.get(index).ref());
        }
        return Math.max(0, hidden.size() - keep);
    }
}
