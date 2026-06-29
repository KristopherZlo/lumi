package io.github.luma.storage.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

public final class VariantRepository {

    private static final java.lang.reflect.Type VARIANT_LIST_TYPE = new TypeToken<List<ProjectVariant>>() { }.getType();
    private static final java.lang.reflect.Type LEGACY_BRANCH_LIST_TYPE = new TypeToken<List<LegacyBranch>>() { }.getType();

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

        JsonElement root = JsonParser.parseString(Files.readString(layout.variantsFile()));
        if (root != null && root.isJsonObject()) {
            return loadLegacyBranches(root.getAsJsonObject());
        }
        List<ProjectVariant> variants = GsonProvider.gson().fromJson(root, VARIANT_LIST_TYPE);
        return variants == null ? List.of() : variants;
    }

    private static List<ProjectVariant> loadLegacyBranches(JsonObject root) {
        JsonElement branches = root.get("branches");
        if (branches == null || !branches.isJsonArray()) {
            return List.of();
        }
        List<LegacyBranch> legacyBranches = GsonProvider.gson().fromJson(branches, LEGACY_BRANCH_LIST_TYPE);
        if (legacyBranches == null) {
            return List.of();
        }
        return legacyBranches.stream().map(LegacyBranch::toVariant).toList();
    }

    private record LegacyBranch(
            String id,
            String displayName,
            String baseVersionId,
            String headVersionId,
            boolean main,
            Instant createdAt
    ) {

        private ProjectVariant toVariant() {
            String name = this.displayName == null || this.displayName.isBlank() ? this.id : this.displayName;
            return new ProjectVariant(this.id, name, this.baseVersionId, this.headVersionId, this.main, this.createdAt);
        }
    }
}
