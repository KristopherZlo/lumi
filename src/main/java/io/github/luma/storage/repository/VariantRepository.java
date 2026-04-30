package io.github.luma.storage.repository;

import com.google.gson.reflect.TypeToken;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class VariantRepository {

    private static final java.lang.reflect.Type VARIANT_LIST_TYPE = new TypeToken<List<ProjectVariant>>() { }.getType();

    public void save(ProjectLayout layout, List<ProjectVariant> variants) throws IOException {
        Files.createDirectories(layout.root());
        StorageIo.writeAtomically(layout.variantsFile(), output -> output.write(
                GsonProvider.gson().toJson(variants).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public List<ProjectVariant> loadAll(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.variantsFile())) {
            return List.of();
        }

        List<ProjectVariant> variants = GsonProvider.gson().fromJson(Files.readString(layout.variantsFile()), VARIANT_LIST_TYPE);
        return variants == null ? List.of() : variants;
    }
}
