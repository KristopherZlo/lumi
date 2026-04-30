package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalUndoRedoPolicyTest {

    private final ExternalUndoRedoPolicy policy = new ExternalUndoRedoPolicy();

    @Test
    void routesWorldEditAndFaweToNativeCommands() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.NATIVE_TOOL_COMMAND,
                this.policy.decisionForActor("worldedit:builder")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.NATIVE_TOOL_COMMAND,
                this.policy.decisionForActor("fawe")
        );
    }

    @Test
    void routesAxiomToNativeHook() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                this.policy.decisionForActor("axiom:builder")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                this.policy.decisionForActor("Axiom")
        );
    }

    @Test
    void routesAxiomActionIdsToNativeHookEvenWhenActorLooksPlayerDriven() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                this.policy.decisionForAction("player", "axiom-bulldozer-action")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                this.policy.decisionForAction("player", "axiom-buffer-fast-place")
        );
    }

    @Test
    void replaysOtherActorsThroughLumi() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.LUMI_REPLAY,
                this.policy.decisionForActor("player")
        );
    }
}
