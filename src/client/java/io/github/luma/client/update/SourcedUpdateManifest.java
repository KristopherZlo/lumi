package io.github.luma.client.update;

public record SourcedUpdateManifest(String sourceName, UpdateManifest manifest) {

    public SourcedUpdateManifest {
        sourceName = sourceName == null ? "" : sourceName.trim();
    }
}
