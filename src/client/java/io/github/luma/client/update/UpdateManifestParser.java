package io.github.luma.client.update;

import io.github.luma.storage.GsonProvider;

public final class UpdateManifestParser {

    public UpdateManifest parse(String json) {
        if (json == null || json.isBlank()) {
            return new UpdateManifest(1, "", java.util.List.of());
        }
        UpdateManifest manifest = GsonProvider.gson().fromJson(json, UpdateManifest.class);
        if (manifest == null) {
            return new UpdateManifest(1, "", java.util.List.of());
        }
        return manifest;
    }
}
