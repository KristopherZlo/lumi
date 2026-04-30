package io.github.luma.client.input;

/**
 * Chooses whether a tracked action should use Lumi's block replay or a native
 * builder-tool undo command.
 */
public final class ExternalUndoRedoPolicy {

    public Decision decisionForActor(String actor) {
        String normalized = actor == null ? "" : actor.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("worldedit") || normalized.startsWith("fawe")) {
            return Decision.NATIVE_TOOL_COMMAND;
        }
        return Decision.LUMI_REPLAY;
    }

    public enum Decision {
        LUMI_REPLAY,
        NATIVE_TOOL_COMMAND
    }
}
