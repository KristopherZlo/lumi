package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Branch rules over immutable commits; branches never copy world payloads. */
public final class BranchService {
    private static final String HIDDEN_PREFIX = "hidden/";
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public BranchService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public BranchRef create(BranchName name, CommitId at) throws IOException {
        Objects.requireNonNull(name, "name");
        commits.read(Objects.requireNonNull(at, "at"));
        return refs.create(name, at);
    }

    public List<BranchRef> visible() throws IOException {
        return refs.list().stream()
                .filter(ref -> !ref.name().value().startsWith(HIDDEN_PREFIX))
                .sorted(java.util.Comparator.comparing(ref -> ref.name().value()))
                .toList();
    }
}
