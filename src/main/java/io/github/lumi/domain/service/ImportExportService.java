package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.object.CommitCodec;
import io.github.lumi.storage.packageformat.LumiPackageArchive;
import io.github.lumi.storage.packageformat.LumiPackageManifest;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Exports and validates portable commit trees without changing local history. */
public final class ImportExportService {
    private final String dimensionId;
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final WorldObjectGraph graph;
    private final LumiPackageArchive archive = new LumiPackageArchive();
    private final CommitCodec commitCodec = new CommitCodec();

    public ImportExportService(String dimensionId, Path dimensionRepository) {
        this.dimensionId = requireDimensionId(dimensionId);
        Objects.requireNonNull(dimensionRepository, "dimensionRepository");
        objects = new WorldObjectRepository(dimensionRepository);
        commits = new CommitRepository(dimensionRepository);
        graph = new WorldObjectGraph(objects);
    }

    public PackageInspection export(CommitId source, Path target) throws IOException {
        Commit commit = commits.read(Objects.requireNonNull(source, "source"));
        byte[] commitPayload = commits.readCanonical(source);
        Map<ObjectId, Integer> inventory = new HashMap<>();
        for (ObjectId id : graph.scan(commit.tree()).reachable()) {
            inventory.put(id, objects.readCanonical(id).length);
        }
        LumiPackageManifest manifest = new LumiPackageManifest(
                dimensionId, source, commitPayload.length, inventory);
        archive.write(target, manifest, commitPayload, objects::readCanonical);
        return new PackageInspection(manifest, commit);
    }

    public PackageInspection inspect(Path source) throws IOException {
        Commit[] decoded = new Commit[1];
        LumiPackageManifest manifest = archive.read(source, new LumiPackageArchive.PayloadConsumer() {
            @Override
            public void commit(CommitId id, byte[] payload) throws IOException {
                decoded[0] = commitCodec.decode(payload);
            }

            @Override
            public void object(ObjectId id, byte[] payload) {
            }
        });
        return new PackageInspection(
                manifest, Objects.requireNonNull(decoded[0], "package commit"));
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
}
