package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.service.CompareService;
import io.github.lumi.domain.service.MaterialCountService;
import io.github.lumi.domain.service.ZoneScope;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Owns cancellable immutable Compare queries for one dimension repository. */
final class DimensionComparisonQueries {
    private static final int MAX_PREVIEW_SECTIONS = 512;
    private final Path repository;
    private final Executor background;
    private final ZoneService zones;
    private final CurrentWorkspace currentWorkspace;

    DimensionComparisonQueries(
            Path repository,
            Executor background,
            ZoneService zones,
            CurrentWorkspace currentWorkspace) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.background = Objects.requireNonNull(background, "background");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.currentWorkspace = Objects.requireNonNull(
                currentWorkspace, "currentWorkspace");
    }

    CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            BooleanSupplier cancelled) throws IOException {
        return compare(before, after, null, cancelled);
    }

    CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(cancelled, "cancelled");
        UUID workspace = currentWorkspace.read();
        ZoneScope scope = zoneId == null
                ? null : new ZoneScope(zones.require(workspace, zoneId));
        return CompletableFuture.supplyAsync(() ->
                compare(before, after, workspace, scope, cancelled), background);
    }

    private ComparisonSummary compare(
            CommitId before,
            CommitId after,
            UUID workspace,
            ZoneScope scope,
            BooleanSupplier cancelled) {
        try {
            WorldObjectRepository objects = new WorldObjectRepository(repository);
            CommitRepository commits = new CommitRepository(repository);
            if (!commits.read(before).workspaceId().equals(workspace)
                    || !commits.read(after).workspaceId().equals(workspace)) {
                throw new IOException(
                        "Compare commits do not belong to the active workspace");
            }
            CompareService comparisons = new CompareService(
                    objects, commits, new OriginStore(repository));
            var difference = scope == null
                    ? comparisons.compare(before, after, cancelled)
                    : comparisons.compare(before, after, scope, cancelled);
            return new ComparisonSummary(
                    before, after,
                    difference.sections().size(),
                    difference.entities().size(),
                    difference.sections().keySet().stream()
                            .limit(MAX_PREVIEW_SECTIONS).toList(),
                    new MaterialCountService(objects).count(difference, cancelled));
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    @FunctionalInterface
    interface CurrentWorkspace {
        UUID read() throws IOException;
    }
}
