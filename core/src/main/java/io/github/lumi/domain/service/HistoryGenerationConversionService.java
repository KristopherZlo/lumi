package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.HistoryConversionMap.Kind;
import io.github.lumi.storage.repository.HistoryGenerationRepository;
import io.github.lumi.storage.repository.HistoryMetadataTransfer;
import io.github.lumi.storage.repository.HistorySourceSnapshot;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

/** Worker-only conversion. The caller freezes all mutation and maintenance until it returns. */
public final class HistoryGenerationConversionService {
    public enum Phase { ALLOCATED, WORLD_OBJECT, COMMIT, METADATA, VERIFICATION, VERIFIED, PUBLISHED, COMPLETED }
    public record Progress(Phase phase, long completed) { }

    private final HistoryGenerationRepository generations;

    public HistoryGenerationConversionService(Path dimensionRepository) {
        generations = new HistoryGenerationRepository(dimensionRepository);
    }

    public HistoryGeneration convert(WorldDataVersion targetVersion, WorldPayloadConverter adapter,
            Consumer<Progress> progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        HistoryGeneration original = generations.active();
        WorldDataCompatibility.requireForward(original.dataVersion(), targetVersion);
        if (generations.pending().filter(pending -> pending.target().equals(original)).isPresent()) {
            generations.finishConversion(original);
        }
        if (original.dataVersion().equals(targetVersion)) {
            return original;
        }
        Path source = generations.directory(original);
        if (new OperationJournalRepository(source).read().isPresent()) {
            throw new IOException("Resolve interrupted world operations with the original game before upgrading history");
        }
        var snapshots = new HistorySourceSnapshot();
        ObjectId sourceSnapshot = snapshots.capture(source);
        HistoryGeneration prepared = generations.beginConversion(original, targetVersion, sourceSnapshot);
        Path target = generations.directory(prepared);
        progress.accept(new Progress(Phase.ALLOCATED, 0));
        var map = new HistoryConversionMap(target);
        var payloads = new WorldPayloadConversionService(targetVersion, adapter);
        var trees = new WorldTreeConversionService(new WorldObjectRepository(source), new WorldObjectRepository(target),
                map, payloads, count -> progress.accept(new Progress(Phase.WORLD_OBJECT, count)));
        var commits = new CommitHistoryConversionService(source, target, map, trees,
                count -> progress.accept(new Progress(Phase.COMMIT, count)));
        var sourceRefs = new BranchRefRepository(source).list();
        var targetRefs = new BranchRefRepository(target);
        for (BranchRef ref : sourceRefs) {
            targetRefs.initializeSnapshot(new BranchRef(ref.name(), commits.convert(ref.commit()), ref.revision()));
        }
        var sourceTombstones = new TombstoneRepository(source).list();
        var targetTombstones = new TombstoneRepository(target);
        for (CommitTombstone tombstone : sourceTombstones) {
            targetTombstones.create(new CommitTombstone(commits.convert(tombstone.commit()),
                    tombstone.deletedBy(), tombstone.deletedAt()));
        }
        new OriginStore(source).visitBatches(batch -> {
            var converted = new HashMap<HistoryKey, ObjectId>();
            for (var entry : batch.entrySet()) {
                converted.put(entry.getKey(), entry.getKey() instanceof SectionKey
                        ? trees.section(entry.getValue()) : trees.entities(entry.getValue()));
            }
            // Each instance retains only this batch's region metadata.
            new OriginStore(target).registerAll(converted);
        });
        new HistoryMetadataTransfer().copy(source, target);
        if (!sourceRefs.equals(new BranchRefRepository(source).list())
                || !sourceTombstones.equals(new TombstoneRepository(source).list())) {
            throw new IOException("Source history roots changed during conversion");
        }
        progress.accept(new Progress(Phase.METADATA, 0));
        new HistoryConversionVerifier(source, target, map, payloads).verify(
                count -> progress.accept(new Progress(Phase.VERIFICATION, count)));
        verifyRoots(source, target, map);
        new HistoryMetadataTransfer().verify(source, target);
        if (!sourceSnapshot.equals(snapshots.capture(source))) {
            throw new IOException("Source history metadata changed during conversion");
        }
        generations.markVerified(original, prepared);
        progress.accept(new Progress(Phase.VERIFIED, 0));
        generations.publish(original, prepared);
        progress.accept(new Progress(Phase.PUBLISHED, 0));
        generations.finishConversion(prepared);
        progress.accept(new Progress(Phase.COMPLETED, 0));
        return prepared;
    }

    private void verifyRoots(Path source, Path target, HistoryConversionMap mapping) throws IOException {
        if (new BranchRefRepository(source).list().size() != new BranchRefRepository(target).list().size()
                || new TombstoneRepository(source).list().size() != new TombstoneRepository(target).list().size()) {
            throw new IOException("Converted history root inventory differs from source");
        }
        for (var ref : new BranchRefRepository(source).list()) {
            BranchRef expected = new BranchRef(ref.name(), new CommitId(
                    required(mapping, Kind.COMMIT, ref.commit().value())), ref.revision());
            if (!new BranchRefRepository(target).read(ref.name()).filter(expected::equals).isPresent()) {
                throw new IOException("Converted history ref differs from source: " + ref.name());
            }
        }
        for (var tombstone : new TombstoneRepository(source).list()) {
            var expected = new CommitTombstone(new CommitId(
                    required(mapping, Kind.COMMIT, tombstone.commit().value())), tombstone.deletedBy(), tombstone.deletedAt());
            if (!new TombstoneRepository(target).read(expected.commit()).filter(expected::equals).isPresent()) {
                throw new IOException("Converted tombstone differs from source");
            }
        }
        new OriginStore(source).visitBatches(batch -> {
            var origins = new OriginStore(target);
            for (var entry : batch.entrySet()) {
                ObjectId expected = entry.getKey() instanceof SectionKey
                        ? required(mapping, Kind.SECTION, entry.getValue()) : required(mapping, Kind.ENTITIES, entry.getValue());
                if (!origins.read(entry.getKey()).filter(expected::equals).isPresent()) {
                    throw new IOException("Converted origin differs from source: " + entry.getKey());
                }
            }
        });
    }

    private ObjectId required(HistoryConversionMap mapping, Kind kind, ObjectId source) throws IOException {
        return mapping.read(kind, source).orElseThrow(() -> new IOException("Missing converted history root: " + source));
    }
}
