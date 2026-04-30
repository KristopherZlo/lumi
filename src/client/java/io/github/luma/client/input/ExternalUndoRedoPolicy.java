package io.github.luma.client.input;

/**
 * Chooses whether a tracked action should use Lumi's replay path or the
 * originating builder tool's native undo/redo surface.
 */
public final class ExternalUndoRedoPolicy {

    private static final String EXPERIMENTAL_AXIOM_NATIVE_UNDO_REDO = "lumi.experimentalAxiomNativeUndoRedo";

    private final boolean axiomNativeUndoRedoEnabled;

    public ExternalUndoRedoPolicy() {
        this(Boolean.getBoolean(EXPERIMENTAL_AXIOM_NATIVE_UNDO_REDO));
    }

    ExternalUndoRedoPolicy(boolean axiomNativeUndoRedoEnabled) {
        this.axiomNativeUndoRedoEnabled = axiomNativeUndoRedoEnabled;
    }

    public Decision decisionForAction(String actor, String actionId) {
        String normalizedActionId = normalize(actionId);
        if (normalizedActionId.startsWith("axiom-") || normalizedActionId.startsWith("axiom:")) {
            return this.decisionForAxiom();
        }
        return this.decisionForActor(actor);
    }

    public Decision decisionForActor(String actor) {
        String normalized = normalize(actor);
        if (normalized.startsWith("worldedit") || normalized.startsWith("fawe")) {
            return Decision.NATIVE_TOOL_COMMAND;
        }
        if (normalized.startsWith("axiom")) {
            return this.decisionForAxiom();
        }
        return Decision.LUMI_REPLAY;
    }

    private Decision decisionForAxiom() {
        return this.axiomNativeUndoRedoEnabled ? Decision.AXIOM_NATIVE_HOOK : Decision.LUMI_REPLAY;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    public enum Decision {
        LUMI_REPLAY,
        NATIVE_TOOL_COMMAND,
        AXIOM_NATIVE_HOOK
    }
}
