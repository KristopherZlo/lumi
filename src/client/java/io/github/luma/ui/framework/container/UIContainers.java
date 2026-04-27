package io.github.luma.ui.framework.container;

import io.github.luma.ui.framework.core.Sizing;

public final class UIContainers {

    private UIContainers() {
    }

    public static FlowLayout verticalFlow(Sizing horizontalSizing, Sizing verticalSizing) {
        return new FlowLayout(FlowLayout.Direction.VERTICAL, false, horizontalSizing, verticalSizing);
    }

    public static FlowLayout horizontalFlow(Sizing horizontalSizing, Sizing verticalSizing) {
        return new FlowLayout(FlowLayout.Direction.HORIZONTAL, false, horizontalSizing, verticalSizing);
    }

    public static FlowLayout ltrTextFlow(Sizing horizontalSizing, Sizing verticalSizing) {
        return new FlowLayout(FlowLayout.Direction.HORIZONTAL, true, horizontalSizing, verticalSizing);
    }
}
