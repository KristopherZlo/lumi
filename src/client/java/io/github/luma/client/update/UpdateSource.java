package io.github.luma.client.update;

@FunctionalInterface
public interface UpdateSource {

    SourcedUpdateManifest load() throws Exception;
}
