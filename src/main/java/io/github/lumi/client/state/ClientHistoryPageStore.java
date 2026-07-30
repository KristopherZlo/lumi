package io.github.lumi.client.state;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded owner for paged history scopes and optimistic mutable metadata. */
public final class ClientHistoryPageStore {
    private static final int MAX_SCOPES = 16;
    private static final int MAX_METADATA_OVERRIDES = 64;
    private static final Channel DEFAULT_CHANNEL =
            new Channel(new UUID(0L, 0L));
    private final LinkedHashMap<PageScope, Pending> pending =
            new LinkedHashMap<>();
    private final LinkedHashMap<PageScope, HistoryPagePayload> pages =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> revisions = new LinkedHashMap<>();
    private final LinkedHashMap<VersionKey, VersionTags> optimisticTags =
            new LinkedHashMap<>();
    private final LinkedHashMap<VersionKey, String> optimisticNames =
            new LinkedHashMap<>();

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
        payload.versions().forEach(version ->
                reconcile(payload.dimensionId(), version));
        pages.remove(pageScope);
        pages.put(pageScope, payload);
        trim(pages);
        return true;
    }

    public synchronized void replaceVersionTags(
            String dimensionId, CommitId versionId, VersionTags replacement) {
        replace(optimisticTags, dimensionId, versionId,
                Objects.requireNonNull(replacement, "replacement"));
    }

    public synchronized void replaceVersionName(
            String dimensionId, CommitId versionId, String replacement) {
        replace(optimisticNames, dimensionId, versionId,
                Objects.requireNonNull(replacement, "replacement"));
    }

    public synchronized void rejectVersionTags(
            String dimensionId, CommitId versionId) {
        optimisticTags.remove(new VersionKey(dimensionId, versionId));
    }

    public synchronized void rejectVersionName(
            String dimensionId, CommitId versionId) {
        optimisticNames.remove(new VersionKey(dimensionId, versionId));
    }

    public synchronized HistorySnapshotPayload.Version version(
            String dimensionId, HistorySnapshotPayload.Version source) {
        VersionKey key = new VersionKey(dimensionId, source.id());
        String name = optimisticNames.getOrDefault(key, source.message());
        VersionTags tags = optimisticTags.getOrDefault(key, source.tags());
        if (name.equals(source.message()) && tags.equals(source.tags())) {
            return source;
        }
        return new HistorySnapshotPayload.Version(
                source.id(), name, source.author(), source.timestampMillis(),
                source.kind(), tags, source.parents(), source.statistics(),
                source.zoneId());
    }

    public synchronized List<HistorySnapshotPayload.Version> versions(
            String dimensionId,
            List<HistorySnapshotPayload.Version> sources) {
        return sources.stream()
                .map(source -> version(dimensionId, source))
                .toList();
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
        optimisticTags.clear();
        optimisticNames.clear();
    }

    private <T> void replace(
            LinkedHashMap<VersionKey, T> values,
            String dimensionId,
            CommitId versionId,
            T replacement) {
        VersionKey key = new VersionKey(dimensionId, versionId);
        values.remove(key);
        values.put(key, replacement);
        trim(values, MAX_METADATA_OVERRIDES);
    }

    private void reconcile(
            String dimensionId, HistorySnapshotPayload.Version version) {
        VersionKey key = new VersionKey(dimensionId, version.id());
        optimisticTags.computeIfPresent(key, (ignored, value) ->
                value.equals(version.tags()) ? null : value);
        optimisticNames.computeIfPresent(key, (ignored, value) ->
                value.equals(version.message()) ? null : value);
    }

    private static <K, V> void trim(LinkedHashMap<K, V> values) {
        trim(values, MAX_SCOPES);
    }

    private static <K, V> void trim(
            LinkedHashMap<K, V> values, int maximumSize) {
        while (values.size() > maximumSize) {
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

    private record VersionKey(String dimensionId, CommitId versionId) {
        private VersionKey {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(versionId, "versionId");
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
