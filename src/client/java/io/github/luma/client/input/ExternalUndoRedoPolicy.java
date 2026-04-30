package io.github.luma.client.input;

/**
 * Chooses whether a tracked action should use Lumi's replay path or the
 * originating builder tool's native undo/redo surface.
 */
public final class ExternalUndoRedoPolicy {

    public Decision decisionForAction(String actor, String actionId) {
        String normalizedActionId = normalize(actionId);
        if (normalizedActionId.startsWith("axiom-") || normalizedActionId.startsWith("axiom:")) {
            return Decision.AXIOM_NATIVE_HOOK;
        }
        return this.decisionForActor(actor);
    }

    public Decision decisionForActor(String actor) {
        String normalized = normalize(actor);
        if (normalized.startsWith("worldedit") || normalized.startsWith("fawe")) {
            return Decision.NATIVE_TOOL_COMMAND;
        }
        if (normalized.startsWith("axiom")) {
            return Decision.AXIOM_NATIVE_HOOK;
        }
        return Decision.LUMI_REPLAY;
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
