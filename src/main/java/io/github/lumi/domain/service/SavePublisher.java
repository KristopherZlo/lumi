package io.github.lumi.domain.service;

import java.io.IOException;

@FunctionalInterface
public interface SavePublisher {
    SaveResult save(SaveRequest request, CapturedWorldState captured) throws IOException;
}
