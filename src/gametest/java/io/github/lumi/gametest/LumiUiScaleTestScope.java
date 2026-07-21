package io.github.lumi.gametest;

import io.github.lumi.client.ui.LumiUiScale;

/** Restores the process-wide Lumi test scale after one client scenario. */
final class LumiUiScaleTestScope implements AutoCloseable {
    private final String previous;

    private LumiUiScaleTestScope() {
        String property = LumiUiScale.TARGET_GUI_SCALE_PROPERTY;
        previous = System.getProperty(property);
        System.setProperty(property, "1");
    }

    static LumiUiScaleTestScope readableViewport() {
        return new LumiUiScaleTestScope();
    }

    @Override
    public void close() {
        String property = LumiUiScale.TARGET_GUI_SCALE_PROPERTY;
        if (previous == null) System.clearProperty(property);
        else System.setProperty(property, previous);
    }
}
