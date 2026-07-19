package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.service.BlockDifferenceService;
import io.github.lumi.domain.service.CompareService;
import io.github.lumi.domain.service.ZoneScope;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
        return compare(before, after, null, cancelled, ignored -> { });
    }

    CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        return compare(before, after, null, cancelled, batches);
    }

    CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled) throws IOException {
        return compare(before, after, zoneId, cancelled, ignored -> { });
    }

    CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(batches, "batches");
        UUID workspace = currentWorkspace.read();
        ZoneScope scope = zoneId == null
                ? null : new ZoneScope(zones.require(workspace, zoneId));
        return CompletableFuture.supplyAsync(() ->
                compare(before, after, workspace, scope, cancelled, batches), background);
    }

    private ComparisonSummary compare(
            CommitId before,
            CommitId after,
            UUID workspace,
            ZoneScope scope,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) {
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
            var blocks = new BlockDifferenceService(objects)
                    .scan(difference, cancelled, batches);
            return new ComparisonSummary(
                    before, after,
                    difference.sections().size(),
                    difference.entities().size(),
                    blocks.changedBlocks(),
                    difference.sections().keySet().stream()
                            .limit(MAX_PREVIEW_SECTIONS).toList(),
                    blocks.materials());
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    @FunctionalInterface
    interface CurrentWorkspace {
        UUID read() throws IOException;
    }
}
