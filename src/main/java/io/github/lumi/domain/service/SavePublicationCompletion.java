package io.github.lumi.domain.service;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.IOException;

/** Makes the captured working-index boundary durable before a Save journal closes. */
@FunctionalInterface
public interface SavePublicationCompletion {
    void complete(WorkingIndexSnapshot captured) throws IOException;
}
