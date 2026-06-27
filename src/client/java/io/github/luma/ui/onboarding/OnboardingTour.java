package io.github.luma.ui.onboarding;

import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.OnboardingFlowState;
import io.github.luma.ui.state.OnboardingHoldGate;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Stateful client-side onboarding tour shared by standalone, workspace, and
 * in-world teaching surfaces.
 */
public final class OnboardingTour {

    private static final int MIN_DIALOG_WIDTH = 300;
    private static final int MAX_DIALOG_WIDTH = 320;
    private static final List<Page> PAGES = List.of(
            Page.info("welcome"),
            Page.world("break_block"),
            Page.world("preview_changes"),
            Page.hold(
                    "undo_world",
                    Transition.EXECUTE_UNDO,
                    "luma.onboarding.hold_undo",
                    LumiClientKeyBindings.Role.ACTION,
                    LumiClientKeyBindings.Role.UNDO
            ),
            Page.hold(
                    "redo_world",
                    Transition.EXECUTE_REDO,
                    "luma.onboarding.hold_redo",
                    LumiClientKeyBindings.Role.ACTION,
                    LumiClientKeyBindings.Role.REDO
            ),
            Page.hold(
                    "save_shortcut",
                    Transition.OPEN_QUICK_SAVE,
                    "luma.onboarding.hold_quick_save",
                    LumiClientKeyBindings.Role.ACTION,
                    LumiClientKeyBindings.Role.QUICK_SAVE
            ),
            Page.hold(
                    "open",
                    Transition.OPEN_WORKSPACE,
                    "luma.onboarding.hold_open",
                    LumiClientKeyBindings.Role.OPEN_WORKSPACE
            ),
            Page.spotlight("save_spotlight", SpotlightTarget.SAVE_BUILD),
            Page.spotlight("changes_spotlight", SpotlightTarget.SEE_CHANGES),
            Page.spotlight("commit_navigation", SpotlightTarget.LATEST_SAVE),
            Page.info("finish")
    );

    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private OnboardingFlowState flow = OnboardingFlowState.first(PAGES.size());
    private long lastHoldSampleMillis;

    public static int pageCount() {
        return PAGES.size();
    }

    public static List<String> pageIds() {
        return PAGES.stream().map(Page::id).toList();
    }

    public Transition tick() {
        Page page = this.currentPage();
        ShortcutCheck check = page.shortcutCheck();
        if (check == null || this.shortcutUnbound(check.shortcut())) {
            this.resetHoldGate();
            return Transition.NONE;
        }

        boolean held = this.shortcutHeld(check.shortcut());
        long now = Util.getMillis();
        long elapsedMillis = held && this.lastHoldSampleMillis > 0L
                ? Math.min(100L, now - this.lastHoldSampleMillis)
                : 0L;
        this.lastHoldSampleMillis = held ? now : 0L;
        if (!this.holdGate.update(held, elapsedMillis)) {
            return Transition.NONE;
        }

        this.resetHoldGate();
        return this.advanceAfterHold(page);
    }

    public FlowLayout panel(int screenWidth, Actions actions) {
        int dialogWidth = this.dialogWidth(screenWidth);
        int contentWidth = Math.max(1, dialogWidth - 16);
        FlowLayout frame = LumaUi.modalFrame(dialogWidth);
        if (this.hidden()) {
            return frame;
        }

        Page page = this.currentPage();
        ShortcutCheck check = page.shortcutCheck();
        frame.child(this.header(page, contentWidth, actions));
        frame.child(this.wrappedValue(Component.translatable(page.helpKey()), contentWidth));
        if (check != null) {
            frame.child(this.holdRow(check, contentWidth));
        }
        if (page.shortcutsTable()) {
            frame.child(this.shortcutsTable(contentWidth));
        }
        frame.child(this.actions(page, check, actions));
        if (check != null && !this.shortcutUnbound(check.shortcut())) {
            frame.child(new OnboardingHoldProgressComponent(this.holdGate::progress));
        }
        return frame;
    }

    public Transition next() {
        return this.nextPage();
    }

    public Transition back() {
        if (!this.canGoBack()) {
            return Transition.NONE;
        }
        this.flow = this.flow.previous();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    public boolean canGoBack() {
        if (this.flow.firstPage()) {
            return false;
        }
        return switch (this.currentPage().id()) {
            case "save_spotlight" -> false;
            default -> true;
        };
    }

    public Transition advanceAfterWorldEdit() {
        if (!"break_block".equals(this.currentPage().id())) {
            return Transition.NONE;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    public Transition advanceAfterPendingPreview() {
        if (!"preview_changes".equals(this.currentPage().id())) {
            return Transition.NONE;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    public Transition advanceAfterQuickSave() {
        if (!"save_shortcut".equals(this.currentPage().id())) {
            return Transition.NONE;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    public boolean hidden() {
        return false;
    }

    public String currentPageId() {
        return this.currentPage().id();
    }

    public int displayIndex() {
        return this.flow.displayIndex();
    }

    public int pageCountValue() {
        return this.flow.pageCount();
    }

    public Component headerText() {
        return Component.translatable("luma.onboarding.header", this.flow.displayIndex(), this.flow.pageCount());
    }

    public Component pageName() {
        return Component.translatable("luma.onboarding.topic_" + this.currentPage().id());
    }

    public Component helpText() {
        return Component.translatable(this.currentPage().helpKey());
    }

    public SpotlightTarget workspaceSpotlightTarget() {
        return this.currentPage().spotlightTarget();
    }

    private FlowLayout header(Page page, int contentWidth, Actions actions) {
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(2);
        header.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        int textWidth = Math.max(1, contentWidth - 28);
        text.gap(2);
        text.child(this.wrappedCaption(this.headerText(), textWidth));
        text.child(this.wrappedAccent(Component.translatable("luma.onboarding.topic_" + page.id()), textWidth));
        header.child(text);

        ButtonComponent close = LumaUi.closeButton(button -> actions.handle(Transition.COMPLETE));
        close.sizing(Sizing.fixed(20), Sizing.fixed(18));
        header.child(close);
        return header;
    }

    private FlowLayout holdRow(ShortcutCheck check, int contentWidth) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.gap(4);
        row.child(this.wrappedCaption(Component.translatable(check.instructionKey()), contentWidth));
        this.addShortcutVisuals(row, check.shortcut());
        return row;
    }

    private FlowLayout shortcutsTable(int contentWidth) {
        FlowLayout table = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        table.child(this.shortcutTableRow(
                "luma.onboarding.shortcuts_open_workspace",
                "luma.onboarding.shortcuts_open_workspace_help",
                new Shortcut(List.of(LumiClientKeyBindings.Role.OPEN_WORKSPACE)),
                contentWidth
        ));
        table.child(this.shortcutTableRow(
                "luma.onboarding.shortcuts_quick_save",
                "luma.onboarding.shortcuts_quick_save_help",
                new Shortcut(List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.QUICK_SAVE)),
                contentWidth
        ));
        table.child(this.shortcutTableRow(
                "luma.onboarding.shortcuts_undo",
                "luma.onboarding.shortcuts_undo_help",
                new Shortcut(List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.UNDO)),
                contentWidth
        ));
        table.child(this.shortcutTableRow(
                "luma.onboarding.shortcuts_redo",
                "luma.onboarding.shortcuts_redo_help",
                new Shortcut(List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.REDO)),
                contentWidth
        ));
        table.child(this.shortcutTableRow(
                "luma.onboarding.shortcuts_quick_rollback",
                "luma.onboarding.shortcuts_quick_rollback_help",
                new Shortcut(List.of(LumiClientKeyBindings.Role.QUICK_ROLLBACK)),
                contentWidth
        ));
        return table;
    }

    private FlowLayout shortcutTableRow(String labelKey, String helpKey, Shortcut shortcut, int contentWidth) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.gap(6);

        FlowLayout keys = UIContainers.horizontalFlow(Sizing.fixed(84), Sizing.content());
        keys.verticalAlignment(VerticalAlignment.CENTER);
        keys.gap(3);
        this.addShortcutVisuals(keys, shortcut);
        row.child(keys);

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        int textWidth = Math.max(90, contentWidth - 110);
        text.gap(1);
        text.child(this.wrappedValue(Component.translatable(labelKey), textWidth));
        text.child(this.wrappedCaption(Component.translatable(helpKey), textWidth));
        row.child(text);
        return row;
    }

    private LabelComponent wrappedValue(Component text, int maxWidth) {
        return LumaUi.value(text).maxWidth(maxWidth);
    }

    private LabelComponent wrappedAccent(Component text, int maxWidth) {
        return LumaUi.accent(text).maxWidth(maxWidth);
    }

    private LabelComponent wrappedCaption(Component text, int maxWidth) {
        return LumaUi.caption(text).maxWidth(maxWidth);
    }

    private void addShortcutVisuals(FlowLayout row, Shortcut shortcut) {
        for (int index = 0; index < shortcut.roles().size(); index++) {
            if (index > 0) {
                row.child(LumaUi.caption(Component.literal("+")));
            }
            row.child(this.keyVisual(shortcut.roles().get(index)));
        }
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

    private FlowLayout actions(Page page, ShortcutCheck check, Actions actions) {
        FlowLayout row = LumaUi.actionRow();
        ButtonComponent back = LumaUi.button(Component.translatable("luma.action.back"), button ->
                actions.handle(this.back()));
        back.active(this.canGoBack());
        row.child(back);

        if (check != null && this.shortcutUnbound(check.shortcut())) {
            row.child(LumaUi.button(
                    Component.translatable("luma.action.open_controls"),
                    button -> actions.handle(Transition.OPEN_CONTROLS)
            ));
            row.child(LumaUi.button(
                    Component.translatable("luma.action.skip"),
                    button -> actions.handle(this.skipShortcutPage(page))
            ));
        }

        String key = this.flow.lastPage() ? "luma.action.finish" : "luma.action.next";
        ButtonComponent next = LumaUi.primaryButton(Component.translatable(key), button ->
                actions.handle(this.flow.lastPage() ? Transition.COMPLETE : this.nextPage()));
        next.active(this.canAdvance(page));
        row.child(next);
        return row;
    }

    private Transition nextPage() {
        Page page = this.currentPage();
        if (!this.canAdvance(page)) {
            return Transition.NONE;
        }
        if (page.worldStep()) {
            this.resetHoldGate();
            return Transition.CLOSE_WORKSPACE;
        }
        if (this.flow.lastPage()) {
            return Transition.COMPLETE;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return Transition.REBUILD;
    }

    private Transition skipShortcutPage(Page page) {
        return switch (page.onHold()) {
            case OPEN_WORKSPACE -> this.advanceAfterHold(page);
            case EXECUTE_UNDO, EXECUTE_REDO -> this.nextPage();
            default -> this.nextPage();
        };
    }

    private Transition advanceAfterHold(Page page) {
        if (this.flow.lastPage()) {
            return Transition.COMPLETE;
        }
        if (page.onHold() == Transition.OPEN_QUICK_SAVE) {
            this.resetHoldGate();
            return page.onHold();
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        return page.onHold();
    }

    private boolean canAdvance(Page page) {
        return page.shortcutCheck() == null || this.shortcutUnbound(page.shortcutCheck().shortcut());
    }

    private boolean shortcutHeld(Shortcut shortcut) {
        for (LumiClientKeyBindings.Role role : shortcut.roles()) {
            KeyMapping key = LumiClientKeyBindings.key(role);
            if (key == null || key.isUnbound() || !this.keyBindingState.isDown(Minecraft.getInstance(), key)) {
                return false;
            }
        }
        return true;
    }

    private boolean shortcutUnbound(Shortcut shortcut) {
        for (LumiClientKeyBindings.Role role : shortcut.roles()) {
            KeyMapping key = LumiClientKeyBindings.key(role);
            if (key == null || key.isUnbound()) {
                return true;
            }
        }
        return false;
    }

    private Page currentPage() {
        return PAGES.get(this.flow.pageIndex());
    }

    private void resetHoldGate() {
        this.holdGate.reset();
        this.lastHoldSampleMillis = 0L;
    }

    private int dialogWidth(int screenWidth) {
        int availableWidth = Math.max(1, screenWidth - 20);
        if (availableWidth < MIN_DIALOG_WIDTH) {
            return availableWidth;
        }
        return Math.min(MAX_DIALOG_WIDTH, availableWidth);
    }

    public enum SpotlightTarget {
        NONE,
        SAVE_BUILD,
        SEE_CHANGES,
        LATEST_SAVE
    }

    public enum Transition {
        NONE,
        REBUILD,
        OPEN_WORKSPACE,
        CLOSE_WORKSPACE,
        OPEN_CONTROLS,
        OPEN_QUICK_SAVE,
        EXECUTE_UNDO,
        EXECUTE_REDO,
        COMPLETE
    }

    public interface Actions {
        void handle(Transition transition);
    }

    private record Page(
            String id,
            ShortcutCheck shortcutCheck,
            Transition onHold,
            SpotlightTarget spotlightTarget,
            boolean worldStep,
            boolean shortcutsTable
    ) {
        private String helpKey() {
            return "luma.onboarding." + this.id + "_help";
        }

        private static Page info(String id) {
            return info(id, false);
        }

        private static Page info(String id, boolean shortcutsTable) {
            return new Page(id, null, Transition.NONE, SpotlightTarget.NONE, false, shortcutsTable);
        }

        private static Page world(String id) {
            return new Page(id, null, Transition.NONE, SpotlightTarget.NONE, true, false);
        }

        private static Page hold(
                String id,
                Transition onHold,
                String instructionKey,
                LumiClientKeyBindings.Role... roles
        ) {
            return new Page(
                    id,
                    new ShortcutCheck(instructionKey, new Shortcut(List.of(roles))),
                    onHold,
                    SpotlightTarget.NONE,
                    false,
                    false
            );
        }

        private static Page spotlight(String id, SpotlightTarget target) {
            return new Page(id, null, Transition.NONE, target, false, false);
        }
    }

    private record ShortcutCheck(String instructionKey, Shortcut shortcut) {
    }

    private record Shortcut(List<LumiClientKeyBindings.Role> roles) {
    }
}
