package io.github.lumi.client.state;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded latest-request-wins owner for independently paged history scopes. */
public final class ClientHistoryPageStore {
    private static final int MAX_SCOPES = 16;
    private final LinkedHashMap<Scope, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Scope, HistoryPagePayload> pages =
            new LinkedHashMap<>();

    public synchronized void begin(
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId,
            int offset) {
        Scope scope = new Scope(dimensionId, workspaceId, branch, zoneId);
        pending.remove(scope);
        pending.put(scope, new Pending(requestId, offset));
        pages.remove(scope);
        trim(pending);
    }

    public synchronized boolean accept(HistoryPagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        Scope scope = new Scope(
                payload.dimensionId(), payload.workspaceId(),
                payload.branch(), payload.zoneId());
        Pending expected = pending.get(scope);
        if (expected == null
                || !expected.requestId().equals(payload.requestId())
                || expected.offset() != payload.offset()) {
            return false;
        }
        pending.remove(scope);
        pages.remove(scope);
        pages.put(scope, payload);
        trim(pages);
        return true;
    }

    public synchronized Optional<HistoryPagePayload> page(
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId) {
        return Optional.ofNullable(pages.get(new Scope(
                dimensionId, workspaceId, branch, zoneId)));
    }

    public synchronized void clear() {
        pending.clear();
        pages.clear();
    }

    private static <K, V> void trim(LinkedHashMap<K, V> values) {
        while (values.size() > MAX_SCOPES) {
            values.remove(values.keySet().iterator().next());
        }
    }

    private record Pending(UUID requestId, int offset) { }

    private record Scope(
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId) {
        private Scope {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(branch, "branch");
            zoneId = Objects.requireNonNull(zoneId, "zoneId");
        }
    }
}
