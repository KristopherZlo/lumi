package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.IOException;

@FunctionalInterface
public interface CapturedGenerationCompletion {
    void clear(WorkingIndexSnapshot captured) throws IOException;
}
