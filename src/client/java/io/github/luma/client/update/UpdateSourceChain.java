package io.github.luma.client.update;

import java.io.IOException;
import java.util.List;

public final class UpdateSourceChain implements UpdateSource {

    private final List<UpdateSource> sources;

    public UpdateSourceChain(List<UpdateSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    @Override
    public SourcedUpdateManifest load() throws Exception {
        Exception lastFailure = null;
        for (UpdateSource source : this.sources) {
            if (source == null) {
                continue;
            }
            try {
                SourcedUpdateManifest manifest = source.load();
                if (manifest != null && manifest.manifest() != null) {
                    return manifest;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("No update manifest source is configured");
    }
}
