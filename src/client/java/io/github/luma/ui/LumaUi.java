package io.github.luma.ui;

import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.network.chat.Component;

public final class LumaUi {

    public static final Color TEXT_PRIMARY = Color.ofRgb(0xF3F7FA);
    public static final Color TEXT_MUTED = Color.ofRgb(0x98A6B3);
    public static final Color TEXT_ACCENT = Color.ofRgb(0x71D1FF);

    private LumaUi() {
    }

    public static FlowLayout panel(Sizing horizontal, Sizing vertical) {
        FlowLayout layout = UIContainers.verticalFlow(horizontal, vertical);
        layout.surface(Surface.DARK_PANEL);
        layout.padding(Insets.of(8));
        layout.gap(6);
        return layout;
    }

    public static FlowLayout insetPanel(Sizing horizontal, Sizing vertical) {
        FlowLayout layout = UIContainers.verticalFlow(horizontal, vertical);
        layout.surface(Surface.PANEL_INSET);
        layout.padding(Insets.of(6));
        layout.gap(4);
        return layout;
    }

    public static FlowLayout chip(Component text) {
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.surface(Surface.PANEL_INSET);
        chip.padding(Insets.of(4));
        chip.child(caption(text));
        return chip;
    }

    public static FlowLayout metric(Component label, Component value) {
        FlowLayout metric = insetPanel(Sizing.content(), Sizing.content());
        metric.child(value(value));
        metric.child(caption(label));
        return metric;
    }

    public static LabelComponent title(Component text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(false);
    }

    public static LabelComponent value(Component text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(true);
    }

    public static LabelComponent accent(Component text) {
        return UIComponents.label(text).color(TEXT_ACCENT).shadow(false);
    }

    public static LabelComponent caption(Component text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false);
    }
}
