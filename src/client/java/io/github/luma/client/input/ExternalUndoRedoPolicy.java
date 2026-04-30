package io.github.luma.client.input;

/**
 * Chooses whether a tracked action should use Lumi's replay path or the
 * originating builder tool's native undo/redo surface.
 */
public final class ExternalUndoRedoPolicy {

    public Decision decisionForActor(String actor) {
        String normalized = actor == null ? "" : actor.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("worldedit") || normalized.startsWith("fawe")) {
            return Decision.NATIVE_TOOL_COMMAND;
        }
        if (normalized.startsWith("axiom")) {
            return Decision.AXIOM_NATIVE_HOOK;
        }
        return Decision.LUMI_REPLAY;
    }

    public enum Decision {
        LUMI_REPLAY,
        NATIVE_TOOL_COMMAND,
        AXIOM_NATIVE_HOOK
    }
}
