package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.object.CommitCodec;
import io.github.lumi.storage.packageformat.LumiPackageArchive;
import io.github.lumi.storage.packageformat.LumiPackageManifest;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.RefConflictException;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exports and validates portable commit trees without changing local history. */
public final class ImportExportService {
    private final String dimensionId;
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final WorldObjectGraph graph;
    private final BranchRefRepository refs;
    private final MerkleTreeEditor trees;
    private final VersionPreviewRepository previews;
    private final LumiPackageArchive archive = new LumiPackageArchive();
    private final CommitCodec commitCodec = new CommitCodec();

    public ImportExportService(String dimensionId, Path dimensionRepository) {
        this.dimensionId = requireDimensionId(dimensionId);
        Objects.requireNonNull(dimensionRepository, "dimensionRepository");
        objects = new WorldObjectRepository(dimensionRepository);
        commits = new CommitRepository(dimensionRepository);
        graph = new WorldObjectGraph(objects);
        refs = new BranchRefRepository(dimensionRepository);
        trees = new MerkleTreeEditor(objects);
        previews = new VersionPreviewRepository(dimensionRepository);
    }

    public PackageInspection export(
            CommitId source, Path target, boolean includePreview) throws IOException {
        Commit commit = commits.read(Objects.requireNonNull(source, "source"));
        byte[] commitPayload = commits.readCanonical(source);
        Map<ObjectId, Integer> inventory = new HashMap<>();
        for (ObjectId id : graph.scan(commit.tree()).reachable()) {
            inventory.put(id, objects.readCanonical(id).length);
        }
        Optional<byte[]> preview = includePreview
                ? previews.load(source) : Optional.empty();
        LumiPackageManifest manifest = new LumiPackageManifest(
                dimensionId, source, commitPayload.length, inventory,
                preview.map(payload -> new LumiPackageManifest.Preview(
                        ObjectId.hash(payload), payload.length)));
        archive.write(
                target, manifest, commitPayload, objects::readCanonical, preview);
        return new PackageInspection(manifest, commit);
    }

    public PackageInspection inspect(Path source) throws IOException {
        return readPackage(source, false, null).inspection();
    }

    public synchronized ImportResult importPackage(
            Path source,
            PackageInspection expected,
            BranchRef base,
            BranchName target,
            CommitAuthor author,
            Instant timestamp) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(timestamp, "timestamp");
        if (!expected.manifest().dimensionId().equals(dimensionId)) {
            throw new IOException("Lumi package belongs to another dimension");
        }
        requireCurrent(base);
        if (refs.read(target).isPresent()) {
            throw new RefConflictException("Import branch already exists: " + target);
        }
        PackageRead actual = readPackage(
                source, true, expected.manifest());
        if (!actual.inspection().equals(expected)) {
            throw new IOException("Lumi package changed after confirmation");
        }
        var importedTree = graph.scan(actual.inspection().source().tree());
        Commit localBase = commits.read(base.commit());
        ObjectId tree = trees.update(
                Optional.of(localBase.tree()), importedTree.leaves());
        CommitStatistics statistics = statistics(importedTree.leaves());
        CommitId commit = commits.write(new Commit(
                tree, List.of(base.commit()), author,
                actual.inspection().source().message(),
                timestamp, localBase.workspaceId(), Optional.empty(),
                CommitKind.IMPORT, statistics,
                actual.inspection().source().playerSpawns()));
        if (actual.preview().isPresent()) {
            previews.save(commit, actual.preview().orElseThrow());
        }
        requireCurrent(base);
        return new ImportResult(commit, refs.create(target, commit));
    }

    private PackageRead readPackage(
            Path source,
            boolean persistObjects,
            LumiPackageManifest expected) throws IOException {
        Commit[] decoded = new Commit[1];
        byte[][] preview = new byte[1][];
        LumiPackageManifest manifest = archive.read(
                source, expected, new LumiPackageArchive.PayloadConsumer() {
            @Override
            public void commit(CommitId id, byte[] payload) throws IOException {
                decoded[0] = commitCodec.decode(payload);
            }

            @Override
            public void object(ObjectId id, byte[] payload) throws IOException {
                if (persistObjects) {
                    objects.writeCanonical(id, payload);
                }
            }

            @Override
            public void preview(CommitId id, byte[] png) {
                preview[0] = png;
            }
        });
        return new PackageRead(new PackageInspection(
                manifest, Objects.requireNonNull(decoded[0], "package commit")),
                Optional.ofNullable(preview[0]));
    }

    private void requireCurrent(BranchRef expected) throws IOException {
        BranchRef actual = refs.read(expected.name()).orElseThrow(
                () -> new RefConflictException("Import base branch is missing"));
        if (!actual.equals(expected)) {
            throw new RefConflictException("Import base branch changed");
        }
    }

    private CommitStatistics statistics(Map<io.github.lumi.domain.model.HistoryKey, ObjectId> leaves)
            throws IOException {
        int sections = 0;
        int entityChunks = 0;
        int entities = 0;
        for (var entry : leaves.entrySet()) {
            if (entry.getKey() instanceof SectionKey) {
                sections = Math.incrementExact(sections);
            } else if (entry.getKey() instanceof EntityChunkKey) {
                entityChunks = Math.incrementExact(entityChunks);
                entities = Math.addExact(
                        entities, objects.readEntities(entry.getValue()).entities().size());
            }
        }
        return new CommitStatistics(
                sections, entityChunks,
                Math.multiplyExact((long) sections, SectionBlob.BLOCK_COUNT),
                entities);
    }

    private static String requireDimensionId(String value) {
        Objects.requireNonNull(value, "dimensionId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Dimension ID cannot be blank");
        }
        return value;
    }

    public record PackageInspection(LumiPackageManifest manifest, Commit source) {
        public PackageInspection {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(source, "source");
        }
    }

    public record ImportResult(CommitId commit, BranchRef branch) {
        public ImportResult {
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(branch, "branch");
        }
    }

    private record PackageRead(
            PackageInspection inspection, Optional<byte[]> preview) {
        private PackageRead {
            Objects.requireNonNull(inspection, "inspection");
            preview = Objects.requireNonNull(preview, "preview");
        }
    }
}
