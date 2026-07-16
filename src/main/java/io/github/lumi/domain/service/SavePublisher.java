package io.github.lumi.domain.service;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface SavePublisher {
    SaveResult save(SaveRequest request, CapturedWorldState captured) throws IOException;

    default SaveResult save(
            SaveRequest request,
            CapturedWorldState captured,
            Consumer<SavePublicationProgress> progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        return save(request, captured);
    }
}
