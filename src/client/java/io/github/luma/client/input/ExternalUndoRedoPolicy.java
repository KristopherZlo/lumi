package io.github.luma.client.input;

/**
 * Chooses whether a tracked action should use Lumi's block replay or a
 * builder tool's own undo stack.
 */
public final class ExternalUndoRedoPolicy {

    public Decision decisionForActor(String actor) {
        String normalized = actor == null ? "" : actor.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("axiom")) {
            return Decision.AXIOM_OWN_UNDO;
        }
        if (normalized.startsWith("worldedit") || normalized.startsWith("fawe")) {
            return Decision.NATIVE_TOOL_COMMAND;
        }
        return Decision.LUMI_REPLAY;
    }

    public enum Decision {
        LUMI_REPLAY,
        NATIVE_TOOL_COMMAND,
        AXIOM_OWN_UNDO
    }
}
