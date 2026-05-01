package io.github.luma.storage.repository;

import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.StoragePathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

public final class PatchMetaRepository {

    public void save(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        StoragePathPolicy.requireStorageId(metadata.id(), "patch id");
        StorageIo.writeAtomically(layout.patchMetaFile(metadata.id()), output -> output.write(
                GsonProvider.compactGson().toJson(metadata).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public Optional<PatchMetadata> load(ProjectLayout layout, String patchId) throws IOException {
        String requestedId = StoragePathPolicy.requireStorageId(patchId, "patch id");
        if (!Files.exists(layout.patchMetaFile(patchId))) {
            return Optional.empty();
        }
        PatchMetadata metadata = GsonProvider.gson().fromJson(
                Files.readString(layout.patchMetaFile(patchId), StandardCharsets.UTF_8),
                PatchMetadata.class
        );
        if (metadata == null) {
            return Optional.empty();
        }
        String actualId = StoragePathPolicy.requireStorageId(metadata.id(), "patch metadata id");
        if (!requestedId.equals(actualId)) {
            throw new IOException("Patch metadata id mismatch for " + requestedId);
        }
        return Optional.of(metadata);
    }
}
