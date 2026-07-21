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
    private static final Channel DEFAULT_CHANNEL =
            new Channel(new UUID(0L, 0L));
    private final LinkedHashMap<PageScope, Pending> pending =
            new LinkedHashMap<>();
    private final LinkedHashMap<PageScope, HistoryPagePayload> pages =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> revisions = new LinkedHashMap<>();

    public static Channel createChannel() {
        return new Channel(UUID.randomUUID());
    }

    public synchronized void begin(
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId,
            int offset) {
        begin(DEFAULT_CHANNEL, requestId, dimensionId, workspaceId,
                branch, zoneId, offset);
    }

    public synchronized void begin(
            Channel channel,
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId,
            int offset) {
        PageScope scope = new PageScope(
                channel, new Scope(dimensionId, workspaceId, branch, zoneId));
        pending.remove(scope);
        pending.put(scope, new Pending(requestId, offset));
        trim(pending);
    }

    public synchronized boolean accept(HistoryPagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        Scope scope = new Scope(
                payload.dimensionId(), payload.workspaceId(),
                payload.branch(), payload.zoneId());
        PageScope pageScope = pending.entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope))
                .filter(entry -> entry.getValue().requestId().equals(
                        payload.requestId()))
                .filter(entry -> entry.getValue().offset() == payload.offset())
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (pageScope == null) {
            return false;
        }
        pending.remove(pageScope);
        pages.remove(pageScope);
        pages.put(pageScope, payload);
        trim(pages);
        return true;
    }

    public synchronized Optional<HistoryPagePayload> page(
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId) {
        return page(DEFAULT_CHANNEL, dimensionId, workspaceId, branch, zoneId);
    }

    public synchronized Optional<HistoryPagePayload> page(
            Channel channel,
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId) {
        return Optional.ofNullable(pages.get(new PageScope(
                channel, new Scope(
                        dimensionId, workspaceId, branch, zoneId))));
    }

    public synchronized void invalidateDimension(String dimensionId) {
        String target = Objects.requireNonNull(dimensionId, "dimensionId");
        pending.keySet().removeIf(key -> key.scope().dimensionId().equals(target));
        pages.keySet().removeIf(key -> key.scope().dimensionId().equals(target));
        revisions.put(target, revision(target) + 1);
        trim(revisions);
    }

    public synchronized long revision(String dimensionId) {
        return revisions.getOrDefault(
                Objects.requireNonNull(dimensionId, "dimensionId"), 0L);
    }

    public synchronized void clear() {
        pending.clear();
        pages.clear();
        revisions.clear();
    }

    private static <K, V> void trim(LinkedHashMap<K, V> values) {
        while (values.size() > MAX_SCOPES) {
            values.remove(values.keySet().iterator().next());
        }
    }

    private record Pending(UUID requestId, int offset) { }

    public record Channel(UUID id) {
        public Channel {
            Objects.requireNonNull(id, "id");
        }
    }

    private record PageScope(Channel channel, Scope scope) {
        private PageScope {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(scope, "scope");
        }
    }

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
