package io.github.luma.integration.common;

import io.github.luma.domain.model.WorldMutationSource;

public record ObservedExternalToolOperation(
        WorldMutationSource source,
        String actor,
        String actionId,
        boolean accessAllowed
) {

    public ObservedExternalToolOperation(WorldMutationSource source, String actor, String actionId) {
        this(source, actor, actionId, false);
    }

    public ObservedExternalToolOperation {
        source = source == null ? WorldMutationSource.EXTERNAL_TOOL : source;
        actor = actor == null || actor.isBlank() ? "external-tool" : actor;
        actionId = actionId == null || actionId.isBlank() ? "external-tool" : actionId;
    }

    public ObservedExternalToolOperation withAccessAllowed(boolean allowed) {
        return new ObservedExternalToolOperation(this.source, this.actor, this.actionId, allowed);
    }
}
