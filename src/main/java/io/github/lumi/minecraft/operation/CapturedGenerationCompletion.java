package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.SavePublicationCompletion;
import java.io.IOException;

@FunctionalInterface
public interface CapturedGenerationCompletion extends SavePublicationCompletion {
    void clear(WorkingIndexSnapshot captured) throws IOException;

    @Override
    default void complete(WorkingIndexSnapshot captured) throws IOException {
        clear(captured);
    }
}
