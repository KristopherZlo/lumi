package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.RefConflictException;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Branch rules over immutable commits; branches never copy world payloads. */
public final class BranchService {
    private static final String HIDDEN_PREFIX = "hidden/";
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final ActiveBranchRepository active;
    private final WorkingIndexRepository working;

    public BranchService(
            CommitRepository commits,
            BranchRefRepository refs,
            ActiveBranchRepository active,
            WorkingIndexRepository working) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.active = Objects.requireNonNull(active, "active");
        this.working = Objects.requireNonNull(working, "working");
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

    public BranchRef active() throws IOException {
        var selected = active.read().orElseThrow(
                () -> new IOException("Active Lumi branch is missing"));
        BranchRef ref = refs.read(selected.name()).orElseThrow(
                () -> new IOException("Active Lumi branch ref is missing: " + selected.name()));
        commits.read(ref.commit());
        return ref;
    }

    public BranchSwitchPlan prepareSwitch(BranchName target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureClean();
        var expectedActive = active.read().orElseThrow(
                () -> new IOException("Active Lumi branch is missing"));
        BranchRef sourceRef = refs.read(expectedActive.name()).orElseThrow(
                () -> new IOException("Active Lumi branch ref is missing: "
                        + expectedActive.name()));
        BranchRef targetRef = refs.read(target).orElseThrow(
                () -> new IOException("Target Lumi branch is missing: " + target));
        commits.read(sourceRef.commit());
        commits.read(targetRef.commit());
        return new BranchSwitchPlan(expectedActive, sourceRef, targetRef);
    }

    public void completeSwitch(BranchSwitchPlan plan) throws IOException {
        validateSwitch(plan);
        active.compareAndSet(plan.expectedActive(), plan.target().name());
    }

    public void validateSwitch(BranchSwitchPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        ensureClean();
        var selected = active.read().orElseThrow(
                () -> new RefConflictException("Active branch no longer exists"));
        if (!selected.equals(plan.expectedActive())) {
            throw new RefConflictException("Active branch changed during switch");
        }
        BranchRef source = refs.read(plan.source().name()).orElseThrow(
                () -> new RefConflictException("Source branch no longer exists"));
        BranchRef target = refs.read(plan.target().name()).orElseThrow(
                () -> new RefConflictException("Target branch no longer exists"));
        if (!source.equals(plan.source())) {
            throw new RefConflictException("Source branch changed during switch");
        }
        if (!target.equals(plan.target())) {
            throw new RefConflictException("Target branch changed during switch");
        }
    }

    private void ensureClean() throws IOException {
        if (!working.read().generations().isEmpty()) {
            throw new IllegalStateException("Branch switch requires no pending world changes");
        }
    }
}
