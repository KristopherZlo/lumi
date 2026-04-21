package io.github.luma.debug;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectSettings;

public final class LumaDebugLog {

    private static final String GLOBAL_FLAG = "lumi.debug";

    private LumaDebugLog() {
    }

    public static boolean globalEnabled() {
        return Boolean.getBoolean(GLOBAL_FLAG);
    }

    public static boolean enabled(ProjectSettings settings) {
        return globalEnabled() || (settings != null && settings.debugLoggingEnabled());
    }

    public static boolean enabled(BuildProject project) {
        return project != null && enabled(project.settings());
    }

    public static boolean enabled(OperationHandle handle) {
        return globalEnabled() || (handle != null && handle.debugEnabled());
    }

    public static boolean enabled(OperationSnapshot snapshot) {
        return snapshot != null && enabled(snapshot.handle());
    }

    public static void log(String category, String message, Object... arguments) {
        if (!globalEnabled()) {
            return;
        }
        LumaMod.LOGGER.info("[debug:{}] " + message, prepend(category, arguments));
    }

    public static void log(ProjectSettings settings, String category, String message, Object... arguments) {
        if (!enabled(settings)) {
            return;
        }
        LumaMod.LOGGER.info("[debug:{}] " + message, prepend(category, arguments));
    }

    public static void log(BuildProject project, String category, String message, Object... arguments) {
        if (!enabled(project)) {
            return;
        }
        LumaMod.LOGGER.info("[debug:{}] " + message, prepend(category, arguments));
    }

    public static void log(OperationHandle handle, String category, String message, Object... arguments) {
        if (!enabled(handle)) {
            return;
        }
        LumaMod.LOGGER.info("[debug:{}] " + message, prepend(category, arguments));
    }

    private static Object[] prepend(String category, Object[] arguments) {
        Object[] values = new Object[(arguments == null ? 0 : arguments.length) + 1];
        values[0] = category == null ? "general" : category;
        if (arguments != null && arguments.length > 0) {
            System.arraycopy(arguments, 0, values, 1, arguments.length);
        }
        return values;
    }
}
