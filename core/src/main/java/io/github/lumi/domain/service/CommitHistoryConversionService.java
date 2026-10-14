package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.HistoryConversionMap.Kind;
import io.github.lumi.storage.repository.HistoryConversionStack;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Rebuilds ancestry before descendants and preserves commit metadata and sidecars. */
public final class CommitHistoryConversionService {
    private final CommitRepository source;
    private final CommitRepository target;
    private final Path sourceDirectory;
    private final Path targetDirectory;
    private final HistoryConversionMap mapping;
    private final WorldTreeConversionService trees;
    private final LongConsumer progress;
    private long converted;

    public CommitHistoryConversionService(
            Path sourceDirectory, Path targetDirectory, HistoryConversionMap mapping,
            WorldTreeConversionService trees, LongConsumer progress) {
        this.sourceDirectory = Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        source = new CommitRepository(sourceDirectory);
        target = new CommitRepository(targetDirectory);
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.trees = Objects.requireNonNull(trees, "trees");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public CommitId convert(CommitId root) throws IOException {
        try (var pending = new HistoryConversionStack(targetDirectory)) {
            pending.push(root);
            while (pending.peek().isPresent()) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("History conversion interrupted");
                CommitId id = pending.peek().orElseThrow();
                if (mapping.read(Kind.COMMIT, id.value()).isPresent()) {
                    pending.pop();
                    continue;
                }
                Commit commit = source.read(id);
                var parents = new ArrayList<CommitId>(2);
                for (CommitId parent : commit.parents()) {
                    var mapped = mapping.read(Kind.COMMIT, parent.value());
                    if (mapped.isEmpty()) {
                        pending.push(parent);
                        break;
                    }
                    parents.add(new CommitId(mapped.orElseThrow()));
                }
                if (parents.size() != commit.parents().size()) continue;
                CommitId result = target.write(new Commit(trees.dimension(commit.tree()), parents,
                        commit.author(), commit.message(), commit.timestamp(), commit.workspaceId(),
                        commit.zoneId(), commit.kind(), commit.statistics(), commit.playerSpawns()));
                copySidecars(id, result);
                mapping.record(Kind.COMMIT, id.value(), result.value());
                pending.pop();
                progress.accept(++converted);
            }
        }
        return new CommitId(mapping.read(Kind.COMMIT, root.value()).orElseThrow());
    }

    private void copySidecars(CommitId original, CommitId converted) throws IOException {
        var name = new VersionDisplayNameRepository(sourceDirectory).read(original);
        if (name.isPresent()) new VersionDisplayNameRepository(targetDirectory).replace(converted, name.orElseThrow());
        var tags = new VersionTagRepository(sourceDirectory).read(original);
        if (!tags.values().isEmpty()) new VersionTagRepository(targetDirectory).replace(converted, tags);
        var preview = new VersionPreviewRepository(sourceDirectory).load(original);
        if (preview.isPresent()) new VersionPreviewRepository(targetDirectory).save(converted, preview.orElseThrow());
    }
}
