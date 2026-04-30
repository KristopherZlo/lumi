package io.github.luma.ui.onboarding;

import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.OnboardingFlowState;
import io.github.luma.ui.state.OnboardingHoldGate;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Stateful client-side onboarding tour shared by the standalone welcome screen
 * and the project workspace overlay.
 */
public final class OnboardingTour {

    private static final int MIN_DIALOG_WIDTH = 320;
    private static final int MAX_DIALOG_WIDTH = 430;
    private static final String OPEN_WORKSPACE_PAGE_ID = "open";
    private static final List<Page> PAGES = List.of(
            Page.info("welcome"),
            Page.shortcut(OPEN_WORKSPACE_PAGE_ID, LumiClientKeyBindings.Role.OPEN_WORKSPACE),
            Page.shortcut("save", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.QUICK_SAVE),
            Page.shortcut("undo", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.UNDO),
            Page.shortcut("redo", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.REDO),
            Page.shortcut("compare", LumiClientKeyBindings.Role.COMPARE),
            Page.shortcut("preview", LumiClientKeyBindings.Role.ACTION),
            Page.info("restore"),
            Page.info("more")
    );

    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private final Set<String> completedShortcutPages = new HashSet<>();
    private OnboardingFlowState flow = OnboardingFlowState.first(PAGES.size());
    private long lastHoldSampleMillis;

    public Transition tick() {
        Page page = this.currentPage();
        if (!this.requiresShortcut(page)) {
            this.resetHoldGate();
            return Transition.NONE;
        }

        boolean held = this.shortcutHeld(page.shortcut());
        long now = Util.getMillis();
        long elapsedMillis = held && this.lastHoldSampleMillis > 0L
                ? Math.min(100L, now - this.lastHoldSampleMillis)
                : 0L;
        this.lastHoldSampleMillis = held ? now : 0L;
        if (this.holdGate.update(held, elapsedMillis)) {
            this.completedShortcutPages.add(page.id());
            return this.advanceAfterShortcut(page);
        }
        return Transition.NONE;
    }

    public FlowLayout panel(int screenWidth, Actions actions) {
        FlowLayout frame = LumaUi.modalFrame(this.dialogWidth(screenWidth));
        Page page = this.currentPage();
        frame.child(this.header(page, actions));
        frame.child(LumaUi.value(Component.translatable(page.titleKey())));
        frame.child(LumaUi.caption(Component.translatable(page.helpKey())));
        if (page.shortcut() != null) {
            frame.child(this.shortcutPanel(page));
        }
        frame.child(this.actions(page, actions));
        if (this.requiresShortcut(page)) {
            frame.child(new OnboardingHoldProgressComponent(this.holdGate::progress));
        }
        return frame;
    }

    private FlowLayout header(Page page, Actions actions) {
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.gap(5);
        header.child(LumaUi.stepBadge(Component.translatable(
                "luma.onboarding.step",
                this.flow.displayIndex(),
                this.flow.pageCount()
        )));

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.caption(Component.translatable("luma.onboarding.label")));
        text.child(LumaUi.accent(Component.translatable("luma.onboarding.topic_" + page.id())));
        header.child(text);

        ButtonComponent close = LumaUi.button(Component.literal("X"), button -> actions.handle(Transition.COMPLETE));
        close.sizing(Sizing.fixed(20), Sizing.fixed(20));
        header.child(close);
        return header;
    }

    private FlowLayout shortcutPanel(Page page) {
        FlowLayout panel = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        panel.child(LumaUi.caption(Component.translatable("luma.onboarding.shortcut_hold")));
        panel.child(this.shortcutRow(page.shortcut()));
        if (this.requiresShortcut(page)) {
            panel.child(LumaUi.caption(Component.translatable("luma.onboarding.shortcut_required")));
        }
        return panel;
    }

    private FlowLayout shortcutRow(Shortcut shortcut) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.horizontalAlignment(HorizontalAlignment.CENTER);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.gap(4);
        for (int index = 0; index < shortcut.roles().size(); index++) {
            if (index > 0) {
                row.child(LumaUi.caption(Component.literal("+")));
            }
            row.child(this.keyVisual(shortcut.roles().get(index)));
        }
        return row;
    }

    private UIComponent keyVisual(LumiClientKeyBindings.Role role) {
        KeyMapping key = LumiClientKeyBindings.key(role);
        return KeyGlyphResolver.resolve(key)
                .<UIComponent>map(glyph -> new KeyGlyphComponent(
                        glyph,
                        () -> this.keyBindingState.isDown(Minecraft.getInstance(), key)
                ))
                .orElseGet(() -> this.keyChip(key));
    }

    private FlowLayout keyChip(KeyMapping key) {
        Component label = key == null || key.isUnbound()
                ? Component.translatable("luma.onboarding.key_unbound")
                : key.getTranslatedKeyMessage();
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.fixed(21));
        chip.verticalAlignment(VerticalAlignment.CENTER);
        chip.child(LumaUi.chip(label));
        return chip;
    }

    private FlowLayout actions(Page page, Actions actions) {
        FlowLayout row = LumaUi.actionRow();
        ButtonComponent back = LumaUi.button(Component.translatable("luma.action.back"), button -> {
            this.previousPage();
            actions.handle(Transition.REBUILD);
        });
        back.active(!this.flow.firstPage());
        row.child(back);

        ButtonComponent next;
        if (this.flow.lastPage()) {
            next = LumaUi.primaryButton(Component.translatable("luma.action.open_lumi"), button ->
                    actions.handle(Transition.COMPLETE));
        } else {
            next = LumaUi.primaryButton(Component.translatable("luma.action.next"), button ->
                    actions.handle(this.nextPage()));
        }
        next.active(this.canAdvance(page));
        row.child(next);
        return row;
    }

    private Transition nextPage() {
        if (!this.canAdvance(this.currentPage())) {
            return Transition.NONE;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    private void previousPage() {
        if (this.flow.firstPage()) {
            return;
        }
        this.flow = this.flow.previous();
        this.resetHoldGate();
    }

    private Transition advanceAfterShortcut(Page page) {
        this.resetHoldGate();
        if (this.flow.lastPage()) {
            return Transition.COMPLETE;
        }
        this.flow = this.flow.next();
        return OPEN_WORKSPACE_PAGE_ID.equals(page.id()) ? Transition.OPEN_WORKSPACE : Transition.REBUILD;
    }

    private boolean canAdvance(Page page) {
        return !this.requiresShortcut(page);
    }

    private boolean requiresShortcut(Page page) {
        return page.shortcut() != null && !this.completedShortcutPages.contains(page.id());
    }

    private boolean shortcutHeld(Shortcut shortcut) {
        if (shortcut == null) {
            return false;
        }
        for (LumiClientKeyBindings.Role role : shortcut.roles()) {
            KeyMapping key = LumiClientKeyBindings.key(role);
            if (key == null || key.isUnbound() || !this.keyBindingState.isDown(Minecraft.getInstance(), key)) {
                return false;
            }
        }
        return true;
    }

    private Page currentPage() {
        return PAGES.get(this.flow.pageIndex());
    }

    private void resetHoldGate() {
        this.holdGate.reset();
        this.lastHoldSampleMillis = 0L;
    }

    private int dialogWidth(int screenWidth) {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, screenWidth - 20));
    }

    public enum Transition {
        NONE,
        REBUILD,
        OPEN_WORKSPACE,
        COMPLETE
    }

    public interface Actions {
        void handle(Transition transition);
    }

    private record Page(String id, String titleKey, String helpKey, Shortcut shortcut) {
        private static Page info(String id) {
            return new Page(id, "luma.onboarding." + id + "_title", "luma.onboarding." + id + "_help", null);
        }

        private static Page shortcut(String id, LumiClientKeyBindings.Role... roles) {
            return new Page(
                    id,
                    "luma.onboarding." + id + "_title",
                    "luma.onboarding." + id + "_help",
                    new Shortcut(List.of(roles))
            );
        }
    }

    private record Shortcut(List<LumiClientKeyBindings.Role> roles) {
    }
}
