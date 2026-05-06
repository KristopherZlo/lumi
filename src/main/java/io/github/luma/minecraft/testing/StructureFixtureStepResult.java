package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.OperationHandle;
import java.util.List;

record StructureFixtureStepResult(List<String> messages, OperationHandle operationHandle, boolean finished) {

    StructureFixtureStepResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    static StructureFixtureStepResult pending(List<String> messages) {
        return new StructureFixtureStepResult(messages, null, false);
    }

    static StructureFixtureStepResult operation(List<String> messages, OperationHandle operationHandle) {
        return new StructureFixtureStepResult(messages, operationHandle, false);
    }

    static StructureFixtureStepResult finished(List<String> messages) {
        return new StructureFixtureStepResult(messages, null, true);
    }
}
