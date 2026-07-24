package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.RefConflictException;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owns hidden refs whose lifetime is exactly one in-memory Undo session. */
public final class SessionCheckpointRefService {
    public static final String PREFIX = "hidden/session-undo/";
    private final BranchRefRepository refs;

    public SessionCheckpointRefService(BranchRefRepository refs) {
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public BranchName name(UUID operationId) {
        return new BranchName(PREFIX + Objects.requireNonNull(
                operationId, "operationId"));
    }

    public boolean release(BranchRef expected) throws IOException {
        requireSessionRef(expected);
        var current = refs.read(expected.name());
        if (current.isEmpty()) {
            return false;
        }
        if (!current.orElseThrow().equals(expected)) {
            throw new RefConflictException(
                    "Session checkpoint changed before release: " + expected.name());
        }
        refs.delete(expected);
        return true;
    }

    public int pruneOrphans(Set<CommitId> retainedCommits) throws IOException {
        Objects.requireNonNull(retainedCommits, "retainedCommits");
        int deleted = 0;
        for (BranchRef ref : refs.list()) {
            if (isSessionRef(ref) && !retainedCommits.contains(ref.commit())) {
                refs.delete(ref);
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean isSessionRef(BranchRef ref) {
        return ref.name().value().startsWith(PREFIX);
    }

    private static void requireSessionRef(BranchRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (!isSessionRef(ref)) {
            throw new IllegalArgumentException(
                    "Ref is not a session checkpoint: " + ref.name());
        }
    }
}
