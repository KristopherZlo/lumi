package io.github.luma.storage.repository;

import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

public final class PatchMetaRepository {

    public void save(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        StorageIo.writeAtomically(layout.patchMetaFile(metadata.id()), output -> output.write(
                GsonProvider.compactGson().toJson(metadata).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public Optional<PatchMetadata> load(ProjectLayout layout, String patchId) throws IOException {
        if (!Files.exists(layout.patchMetaFile(patchId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(GsonProvider.gson().fromJson(
                Files.readString(layout.patchMetaFile(patchId), StandardCharsets.UTF_8),
                PatchMetadata.class
        ));
    }
}
