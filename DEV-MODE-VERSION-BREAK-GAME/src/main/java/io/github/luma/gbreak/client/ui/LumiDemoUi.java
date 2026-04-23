package io.github.luma.gbreak.client.ui;

import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.text.Text;

public final class LumiDemoUi {

    public static final Color TEXT_PRIMARY = Color.ofRgb(0xF3F7FA);
    public static final Color TEXT_MUTED = Color.ofRgb(0x98A6B3);
    public static final Color TEXT_ACCENT = Color.ofRgb(0x71D1FF);
    public static final Color TEXT_DANGER = Color.ofRgb(0xFF8585);
    private static final int VALUE_WRAP_WIDTH = 420;
    private static final int BODY_WRAP_WIDTH = 360;

    private LumiDemoUi() {
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

    public static LumiDemoScrollContainer<FlowLayout> screenScroll(FlowLayout body) {
        LumiDemoScrollContainer<FlowLayout> scroll = LumiDemoScrollContainer.vertical(
                Sizing.fill(100),
                Sizing.expand(100),
                body
        );
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

    public static FlowLayout chip(Text text) {
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.surface(Surface.PANEL_INSET);
        chip.padding(Insets.of(4));
        chip.child(compactCaption(text));
        return chip;
    }

    public static FlowLayout statusBanner(Text text) {
        FlowLayout banner = insetPanel(Sizing.fill(100), Sizing.content());
        banner.child(accent(text));
        return banner;
    }

    public static FlowLayout sectionCard(Text title, Text subtitle) {
        FlowLayout card = panel(Sizing.fill(100), Sizing.content());
        if (title != null) {
            card.child(value(title));
        }
        if (subtitle != null) {
            card.child(caption(subtitle));
        }
        return card;
    }

    public static FlowLayout actionRow() {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        return row;
    }

    public static LabelComponent title(Text text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(false).maxWidth(VALUE_WRAP_WIDTH);
    }

    public static LabelComponent value(Text text) {
        return UIComponents.label(text).color(TEXT_PRIMARY).shadow(true).maxWidth(VALUE_WRAP_WIDTH);
    }

    public static LabelComponent accent(Text text) {
        return UIComponents.label(text).color(TEXT_ACCENT).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    public static LabelComponent danger(Text text) {
        return UIComponents.label(text).color(TEXT_DANGER).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    public static LabelComponent caption(Text text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(BODY_WRAP_WIDTH);
    }

    public static FlowLayout statChip(Text label, Text value) {
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.surface(Surface.PANEL_INSET);
        chip.padding(Insets.of(4));
        chip.gap(4);
        chip.child(UIComponents.label(value).color(TEXT_PRIMARY).shadow(true).maxWidth(180));
        chip.child(compactCaption(label));
        return chip;
    }

    private static LabelComponent compactCaption(Text text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(180);
    }
}
