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
    public static final Color TEXT_DANGER = Color.ofRgb(0xFF8585);
    private static final int VALUE_WRAP_WIDTH = 420;
    private static final int BODY_WRAP_WIDTH = 360;

    private LumaUi() {
    }

    public static FlowLayout screenFrame() {
        FlowLayout layout = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        layout.gap(8);
        return layout;
    }

    public static FlowLayout screenBody() {
        FlowLayout layout = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        layout.padding(Insets.bottom(12));
        layout.gap(8);
        return layout;
    }

    public static LumaScrollContainer<FlowLayout> screenScroll(FlowLayout body) {
        return screenScroll(Sizing.fill(100), Sizing.fill(100), body);
    }

    public static LumaScrollContainer<FlowLayout> screenScroll(
            Sizing horizontalSizing,
            Sizing verticalSizing,
            FlowLayout body
    ) {
        LumaScrollContainer<FlowLayout> scroll = LumaScrollContainer.vertical(horizontalSizing, verticalSizing, body);
        scroll.scrollbarThiccness(6);
        scroll.scrollStep(24);
        return scroll;
    }

    public static FlowLayout bottomSpacer() {
        return UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(18));
    }

    public static FlowLayout panel(Sizing horizontal, Sizing vertical) {
        FlowLayout layout = UIContainers.verticalFlow(horizontal, vertical);
        layout.surface(Surface.DARK_PANEL);
        layout.padding(Insets.of(10));
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
        chip.child(compactCaption(text));
        return chip;
    }

    public static FlowLayout statusBanner(Component text) {
        FlowLayout banner = insetPanel(Sizing.fill(100), Sizing.content());
        banner.child(accent(text));
        return banner;
    }

    public static FlowLayout emptyState(Component title, Component description) {
        FlowLayout state = panel(Sizing.fill(100), Sizing.content());
        state.child(value(title));
        if (description != null) {
            state.child(caption(description));
        }
        return state;
    }

    public static FlowLayout sectionCard(Component title, Component subtitle) {
        FlowLayout card = panel(Sizing.fill(100), Sizing.content());
        if (title != null) {
            card.child(value(title));
        }
        if (subtitle != null) {
            card.child(caption(subtitle));
        }
        return card;
    }

    public static FlowLayout insetSection(Component title, Component subtitle) {
        FlowLayout section = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        section.padding(Insets.of(4));
        section.gap(4);
        if (title != null) {
            section.child(value(title));
        }
        if (subtitle != null) {
            section.child(caption(subtitle));
        }
        return section;
    }

    public static FlowLayout formField(
            Component label,
            Component help,
            io.wispforest.owo.ui.core.UIComponent control
    ) {
        FlowLayout field = insetPanel(Sizing.fill(100), Sizing.content());
        field.child(value(label));
        if (help != null) {
            field.child(caption(help));
        }
        field.child(control);
        return field;
    }

    public static FlowLayout metric(Component label, Component value) {
        FlowLayout metric = insetPanel(Sizing.content(), Sizing.content());
        metric.child(value(value));
        metric.child(caption(label));
        return metric;
    }

    public static FlowLayout statChip(Component label, Component value) {
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.surface(Surface.PANEL_INSET);
        chip.padding(Insets.of(4));
        chip.gap(4);
        chip.child(compactValue(value));
        chip.child(compactCaption(label));
        return chip;
    }

    public static FlowLayout actionRow() {
        FlowLayout row = UIContainers.ltrTextFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        return row;
    }

    public static LabelComponent title(Component text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(false).maxWidth(VALUE_WRAP_WIDTH);
    }

    public static LabelComponent value(Component text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(true).maxWidth(VALUE_WRAP_WIDTH);
    }

    public static LabelComponent accent(Component text) {
        return UIComponents.label(text).color(TEXT_ACCENT).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    public static LabelComponent danger(Component text) {
        return UIComponents.label(text).color(TEXT_DANGER).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    public static LabelComponent caption(Component text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    private static LabelComponent compactValue(Component text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(true).maxWidth(180);
    }

    private static LabelComponent compactCaption(Component text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(180);
    }
}
