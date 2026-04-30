package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalUndoRedoPolicyTest {

    private final ExternalUndoRedoPolicy policy = new ExternalUndoRedoPolicy(false);

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
    void replaysAxiomThroughLumiByDefault() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.LUMI_REPLAY,
                this.policy.decisionForActor("axiom:builder")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.LUMI_REPLAY,
                this.policy.decisionForActor("Axiom")
        );
    }

    @Test
    void replaysAxiomActionIdsThroughLumiByDefaultEvenWhenActorLooksPlayerDriven() {
        assertEquals(
                ExternalUndoRedoPolicy.Decision.LUMI_REPLAY,
                this.policy.decisionForAction("player", "axiom-bulldozer-action")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.LUMI_REPLAY,
                this.policy.decisionForAction("player", "axiom-buffer-fast-place")
        );
    }

    @Test
    void routesAxiomThroughNativeHookOnlyWhenExperimentalFlagIsEnabled() {
        ExternalUndoRedoPolicy experimentalPolicy = new ExternalUndoRedoPolicy(true);

        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                experimentalPolicy.decisionForActor("axiom:builder")
        );
        assertEquals(
                ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK,
                experimentalPolicy.decisionForAction("player", "axiom-buffer-fast-place")
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
