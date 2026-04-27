package io.github.luma.integration.common;

import io.github.luma.domain.model.WorldMutationSource;

public record ObservedExternalToolOperation(
        WorldMutationSource source,
        String actor,
        String actionId
) {

    public ObservedExternalToolOperation {
        source = source == null ? WorldMutationSource.EXTERNAL_TOOL : source;
        actor = actor == null || actor.isBlank() ? "external-tool" : actor;
        actionId = actionId == null || actionId.isBlank() ? "external-tool" : actionId;
    }
}
