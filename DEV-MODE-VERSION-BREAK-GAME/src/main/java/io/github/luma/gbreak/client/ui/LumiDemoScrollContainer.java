package io.github.luma.gbreak.client.ui;

import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;

public final class LumiDemoScrollContainer<C extends UIComponent> extends ScrollContainer<C> {

    private LumiDemoScrollContainer(ScrollDirection direction, Sizing horizontalSizing, Sizing verticalSizing, C child) {
        super(direction, horizontalSizing, verticalSizing, child);
    }

    public static <C extends UIComponent> LumiDemoScrollContainer<C> vertical(
            Sizing horizontalSizing,
            Sizing verticalSizing,
            C child
    ) {
        return new LumiDemoScrollContainer<>(ScrollDirection.VERTICAL, horizontalSizing, verticalSizing, child);
    }
}
