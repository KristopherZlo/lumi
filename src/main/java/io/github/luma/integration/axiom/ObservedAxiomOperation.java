package io.github.luma.integration.axiom;

public record ObservedAxiomOperation(String actor, String actionId) {

    public ObservedAxiomOperation {
        actor = actor == null || actor.isBlank() ? "axiom" : actor;
        actionId = actionId == null || actionId.isBlank() ? "axiom-observed" : actionId;
    }
}
