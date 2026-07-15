package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Read-only ancestry queries over immutable commit parents. */
public final class CommitGraph {
    private final CommitRepository commits;

    public CommitGraph(CommitRepository commits) {
        this.commits = Objects.requireNonNull(commits, "commits");
    }

    public CommitId nearestCommonAncestor(CommitId first, CommitId second) throws IOException {
        Map<CommitId, Integer> firstDistances = distances(first);
        Map<CommitId, Integer> secondDistances = distances(second);
        return firstDistances.keySet().stream()
                .filter(secondDistances::containsKey)
                .min(Comparator
                        .comparingLong((CommitId id) ->
                                (long) firstDistances.get(id) + secondDistances.get(id))
                        .thenComparingInt(id -> Math.max(
                                firstDistances.get(id), secondDistances.get(id)))
                        .thenComparing(CommitId::hex))
                .orElseThrow(() -> new IOException("Commit histories have no common ancestor"));
    }

    private Map<CommitId, Integer> distances(CommitId start) throws IOException {
        Objects.requireNonNull(start, "start");
        Map<CommitId, Integer> distances = new HashMap<>();
        ArrayDeque<CommitId> pending = new ArrayDeque<>();
        distances.put(start, 0);
        pending.add(start);
        while (!pending.isEmpty()) {
            CommitId current = pending.removeFirst();
            int childDistance = distances.get(current) + 1;
            for (CommitId parent : commits.read(current).parents()) {
                if (distances.putIfAbsent(parent, childDistance) == null) {
                    pending.addLast(parent);
                }
            }
        }
        return distances;
    }
}
