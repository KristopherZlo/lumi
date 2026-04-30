package io.github.luma.ui;

import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Easing;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

public final class LumaUi {

    public static final Color TEXT_PRIMARY = Color.ofRgb(0xF4F1EA);
    public static final Color TEXT_MUTED = Color.ofRgb(0xA9A39A);
    public static final Color TEXT_ACCENT = Color.ofRgb(0xD9B86C);
    public static final Color TEXT_DANGER = Color.ofRgb(0xFF8585);
    private static final int BACKDROP_FILL = 0xD608090A;
    private static final int WINDOW_FILL = 0xF0141517;
    private static final int WINDOW_BORDER = 0xFF45413A;
    private static final int TITLEBAR_FILL = 0xFF1C1D20;
    private static final int SIDEBAR_FILL = 0xFF111214;
    private static final int PANEL_FILL = 0xEF1A1B1E;
    private static final int PANEL_BORDER = 0xFF343238;
    private static final int ACTIVE_PANEL_BORDER = 0xFFE0B95A;
    private static final int INSET_FILL = 0xEA101113;
    private static final int INSET_BORDER = 0xFF2B2A2F;
    private static final int CHIP_FILL = 0xFF242326;
    private static final int CHIP_BORDER = 0xFF3C3830;
    private static final int BUTTON_FILL = 0xFF2A292C;
    private static final int BUTTON_HOVER = 0xFF39363A;
    private static final int BUTTON_DISABLED = 0xFF18191B;
    private static final int PRIMARY_BUTTON_FILL = 0xFF7A5A21;
    private static final int PRIMARY_BUTTON_HOVER = 0xFF936D29;
    private static final int STATUS_FILL = 0xFF211F18;
    private static final int STATUS_BORDER = 0xFF5A4724;
    private static final int VALUE_WRAP_WIDTH = 420;
    private static final int BODY_WRAP_WIDTH = 360;

    private LumaUi() {
    }

    public static FlowLayout screenFrame() {
        FlowLayout layout = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        layout.surface(windowSurface());
        layout.padding(Insets.of(5));
        layout.gap(5);
        return layout;
    }

    public static FlowLayout modalFrame(int width) {
        FlowLayout layout = UIContainers.verticalFlow(Sizing.fixed(width), Sizing.content());
        layout.surface(windowSurface());
        layout.padding(Insets.of(6));
        layout.gap(5);
        return layout;
    }

    public static Surface screenBackdrop() {
        return Surface.flat(BACKDROP_FILL);
    }

    public static Surface windowSurface() {
        return Surface.flat(WINDOW_FILL).and(Surface.outline(WINDOW_BORDER));
    }

    public static FlowLayout windowShell() {
        FlowLayout layout = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.fill(100));
        layout.surface(windowSurface());
        layout.gap(0);
        return layout;
    }

    public static FlowLayout windowSidebar(int width) {
        FlowLayout sidebar = UIContainers.verticalFlow(Sizing.fixed(width), Sizing.fill(100));
        sidebar.surface(Surface.flat(SIDEBAR_FILL).and(Surface.outline(0xFF272528)));
        sidebar.padding(Insets.of(6));
        sidebar.gap(5);
        return sidebar;
    }

    public static FlowLayout windowContent() {
        FlowLayout content = UIContainers.verticalFlow(Sizing.expand(100), Sizing.fill(100));
        content.padding(Insets.of(6));
        content.gap(5);
        return content;
    }

    public static FlowLayout titleBar() {
        FlowLayout titleBar = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleBar.surface(Surface.flat(TITLEBAR_FILL).and(Surface.outline(PANEL_BORDER)));
        titleBar.padding(Insets.of(5));
        titleBar.gap(5);
        return titleBar;
    }

    public static FlowLayout screenBody() {
        FlowLayout layout = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        layout.padding(Insets.bottom(10));
        layout.gap(5);
        return layout;
    }

    public static LumaScrollContainer<FlowLayout> screenScroll(FlowLayout body) {
        return screenScroll(Sizing.fill(100), Sizing.expand(100), body);
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
        layout.surface(Surface.flat(PANEL_FILL).and(Surface.outline(PANEL_BORDER)));
        layout.padding(Insets.of(6));
        layout.gap(5);
        return layout;
    }

    public static FlowLayout insetPanel(Sizing horizontal, Sizing vertical) {
        FlowLayout layout = UIContainers.verticalFlow(horizontal, vertical);
        layout.surface(Surface.flat(INSET_FILL).and(Surface.outline(INSET_BORDER)));
        layout.padding(Insets.of(4));
        layout.gap(4);
        return layout;
    }

    public static FlowLayout activeInsetPanel(Sizing horizontal, Sizing vertical) {
        FlowLayout layout = UIContainers.verticalFlow(horizontal, vertical);
        layout.surface(Surface.flat(INSET_FILL).and(Surface.outline(ACTIVE_PANEL_BORDER)));
        layout.padding(Insets.of(4));
        layout.gap(4);
        return layout;
    }

    public static FlowLayout chip(Component text) {
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        chip.surface(Surface.flat(CHIP_FILL).and(Surface.outline(CHIP_BORDER)));
        chip.padding(Insets.of(1));
        chip.child(compactCaption(text));
        return chip;
    }

    public static FlowLayout stepBadge(Component text) {
        FlowLayout badge = UIContainers.horizontalFlow(Sizing.fixed(50), Sizing.fixed(22));
        badge.surface(Surface.flat(STATUS_FILL).and(Surface.outline(STATUS_BORDER)));
        badge.horizontalAlignment(HorizontalAlignment.CENTER);
        badge.verticalAlignment(VerticalAlignment.CENTER);
        badge.padding(Insets.of(3));
        badge.child(compactAccent(text));
        return badge;
    }

    public static FlowLayout statusBanner(Component text) {
        FlowLayout banner = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        banner.surface(Surface.flat(STATUS_FILL).and(Surface.outline(STATUS_BORDER)));
        banner.padding(Insets.of(5));
        banner.gap(3);
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
        section.padding(Insets.of(3));
        section.gap(3);
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
        chip.surface(Surface.flat(CHIP_FILL).and(Surface.outline(CHIP_BORDER)));
        chip.padding(Insets.of(3));
        chip.gap(4);
        chip.child(statValue(value));
        chip.child(statLabel(label));
        return chip;
    }

    public static FlowLayout sidebarTabs() {
        FlowLayout tabs = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        tabs.gap(3);
        tabs.margins(Insets.top(5));
        return tabs;
    }

    public static FlowLayout sidebarSpacer() {
        return UIContainers.verticalFlow(Sizing.fill(100), Sizing.expand(100));
    }

    public static FlowLayout sidebarFooter() {
        FlowLayout footer = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        footer.surface(Surface.flat(INSET_FILL).and(Surface.outline(INSET_BORDER)));
        footer.padding(Insets.of(4));
        footer.gap(4);
        return footer;
    }

    public static ButtonComponent sidebarTab(Component text, boolean selected, Consumer<ButtonComponent> onPress) {
        ButtonComponent button = styledButton(
                text,
                selected ? pressed -> {
                } : onPress,
                selected ? STATUS_FILL : BUTTON_FILL,
                selected ? STATUS_FILL : BUTTON_HOVER,
                selected ? STATUS_FILL : BUTTON_DISABLED
        );
        button.sizing(Sizing.fill(100), Sizing.fixed(20));
        button.active(true);
        return button;
    }

    public static FlowLayout revealGroup() {
        FlowLayout group = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        group.gap(4);
        group.margins(Insets.top(-4));
        group.margins().animate(160, Easing.CUBIC, Insets.none()).forwards();
        return group;
    }

    public static FlowLayout actionRow() {
        FlowLayout row = UIContainers.ltrTextFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        return row;
    }

    public static ButtonComponent button(Component text, Consumer<ButtonComponent> onPress) {
        return styledButton(text, onPress, BUTTON_FILL, BUTTON_HOVER, BUTTON_DISABLED);
    }

    public static ButtonComponent primaryButton(Component text, Consumer<ButtonComponent> onPress) {
        return styledButton(text, onPress, PRIMARY_BUTTON_FILL, PRIMARY_BUTTON_HOVER, BUTTON_DISABLED);
    }

    private static ButtonComponent styledButton(
            Component text,
            Consumer<ButtonComponent> onPress,
            int fill,
            int hover,
            int disabled
    ) {
        ButtonComponent button = UIComponents.button(text, onPress);
        button.renderer(ButtonComponent.Renderer.flat(fill, hover, disabled));
        button.textShadow(false);
        button.sizing(Sizing.content(5), Sizing.fixed(18));
        return button;
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

    private static LabelComponent compactAccent(Component text) {
        return UIComponents.label(text).color(TEXT_ACCENT).shadow(false).maxWidth(180);
    }

    private static LabelComponent compactCaption(Component text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(180);
    }

    private static LabelComponent statValue(Component text) {
        return UIComponents.label(text).color(TEXT_ACCENT).shadow(false).maxWidth(72);
    }

    private static LabelComponent statLabel(Component text) {
        return UIComponents.label(text).color(TEXT_MUTED).shadow(false).maxWidth(108);
    }
}
