package io.github.luma.ui;

import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;

public final class LumaScrollContainer<C extends UIComponent> extends ScrollContainer<C> {

    private LumaScrollContainer(ScrollDirection direction, Sizing horizontalSizing, Sizing verticalSizing, C child) {
        super(direction, horizontalSizing, verticalSizing, child);
    }

    public static <C extends UIComponent> LumaScrollContainer<C> vertical(
            Sizing horizontalSizing,
            Sizing verticalSizing,
            C child
    ) {
        return new LumaScrollContainer<>(ScrollDirection.VERTICAL, horizontalSizing, verticalSizing, child);
    }

    public double progress() {
        if (this.maxScroll <= 0) {
            return 0.0D;
        }
        return this.scrollOffset / this.maxScroll;
    }

    public LumaScrollContainer<C> restoreProgress(double progress) {
        double clampedProgress = Math.max(0.0D, Math.min(1.0D, progress));
        this.scrollOffset = this.maxScroll * clampedProgress;
        this.currentScrollPosition = this.scrollOffset;
        return this;
    }
}
