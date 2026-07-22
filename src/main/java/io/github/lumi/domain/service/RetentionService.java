package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Applies ref retention without rewriting immutable commit history. */
public final class RetentionService {
    private static final List<String> CHECKPOINT_REF_PREFIXES = List.of(
            "hidden/return/",
            "hidden/partial/",
            "hidden/zone/",
            "hidden/branch-switch/",
            "hidden/rollback/");
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public RetentionService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public int pruneAfterPublication(int keep, BranchRef publishedRef) throws IOException {
        if (keep < 1) {
            throw new IllegalArgumentException("Retention count must be positive");
        }
        Objects.requireNonNull(publishedRef, "publishedRef");
        if (!isCheckpointRef(publishedRef)) {
            return 0;
        }
        record HiddenRef(BranchRef ref, Instant timestamp) { }
        var hidden = new ArrayList<HiddenRef>();
        var timestamps = new HashMap<CommitId, Instant>();
        for (BranchRef ref : refs.list()) {
            if (!isCheckpointRef(ref)) {
                continue;
            }
            Instant timestamp = timestamps.get(ref.commit());
            if (timestamp == null) {
                timestamp = commits.read(ref.commit()).timestamp();
                timestamps.put(ref.commit(), timestamp);
            }
            hidden.add(new HiddenRef(ref, timestamp));
        }
        hidden.sort(Comparator.comparing(HiddenRef::timestamp).reversed()
                .thenComparing(item -> item.ref().name().value()));
        int delete = Math.max(0, hidden.size() - keep);
        int deleted = 0;
        for (int index = hidden.size() - 1; index >= 0 && deleted < delete; index--) {
            BranchRef candidate = hidden.get(index).ref();
            if (!candidate.name().equals(publishedRef.name())) {
                refs.delete(candidate);
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean isCheckpointRef(BranchRef ref) {
        String name = ref.name().value();
        return CHECKPOINT_REF_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
