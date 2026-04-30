package io.github.luma.ui.screen;

import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.onboarding.KeyGlyphComponent;
import io.github.luma.ui.onboarding.KeyGlyphResolver;
import io.github.luma.ui.onboarding.OnboardingHoldProgressComponent;
import io.github.luma.ui.state.OnboardingFlowState;
import io.github.luma.ui.state.OnboardingHoldGate;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class OnboardingScreen extends LumaScreen {

    private static final int MIN_DIALOG_WIDTH = 320;
    private static final int MAX_DIALOG_WIDTH = 430;
    private static final List<Page> PAGES = List.of(
            Page.info("welcome"),
            Page.shortcut("open", LumiClientKeyBindings.Role.OPEN_WORKSPACE),
            Page.shortcut("save", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.QUICK_SAVE),
            Page.shortcut("undo", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.UNDO),
            Page.shortcut("redo", LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.REDO),
            Page.shortcut("compare", LumiClientKeyBindings.Role.COMPARE),
            Page.shortcut("preview", LumiClientKeyBindings.Role.ACTION),
            Page.info("restore"),
            Page.info("more")
    );

    private final Screen parent;
    private final String projectName;
    private final String variantId;
    private final String statusKey;
    private final ClientOnboardingService onboardingService;
    private final ScreenRouter router = new ScreenRouter();
    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private final Set<String> completedShortcutPages = new HashSet<>();
    private OnboardingFlowState flow = OnboardingFlowState.first(PAGES.size());
    private long lastHoldSampleMillis;

    public OnboardingScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready", new ClientOnboardingService());
    }

    public OnboardingScreen(Screen parent, String projectName, ClientOnboardingService onboardingService) {
        this(parent, projectName, "", "luma.status.project_ready", onboardingService);
    }

    public OnboardingScreen(
            Screen parent,
            String projectName,
            String variantId,
            String statusKey,
            ClientOnboardingService onboardingService
    ) {
        super(Component.translatable("luma.screen.onboarding.title"));
        this.parent = parent;
        this.projectName = projectName;
        this.variantId = variantId == null ? "" : variantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(this.dialogWidth());
        root.child(frame);

        Page page = PAGES.get(this.flow.pageIndex());
        frame.child(this.header(page));
        frame.child(LumaUi.value(Component.translatable(page.titleKey())));
        frame.child(LumaUi.caption(Component.translatable(page.helpKey())));
        if (page.shortcut() != null) {
            frame.child(this.shortcutPanel(page));
        }
        frame.child(this.actions());
        if (this.requiresShortcut(page)) {
            frame.child(new OnboardingHoldProgressComponent(this.holdGate::progress));
        }
    }

    @Override
    public void onClose() {
        this.completeAndOpenWorkspace();
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    @Override
    protected void onLumaTick() {
        Page page = PAGES.get(this.flow.pageIndex());
        if (!this.requiresShortcut(page)) {
            this.resetHoldGate();
            return;
        }

        boolean held = this.shortcutHeld(page.shortcut());
        long now = Util.getMillis();
        long elapsedMillis = held && this.lastHoldSampleMillis > 0L
                ? Math.min(100L, now - this.lastHoldSampleMillis)
                : 0L;
        this.lastHoldSampleMillis = held ? now : 0L;
        if (this.holdGate.update(held, elapsedMillis)) {
            this.completedShortcutPages.add(page.id());
            this.advanceAfterShortcut();
        }
    }

    private FlowLayout header(Page page) {
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(5);
        header.child(LumaUi.stepBadge(Component.translatable(
                "luma.onboarding.step",
                this.flow.displayIndex(),
                this.flow.pageCount()
        )));
        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.caption(Component.translatable("luma.onboarding.label")));
        text.child(LumaUi.accent(Component.translatable("luma.onboarding.topic_" + page.id())));
        header.child(text);
        ButtonComponent close = LumaUi.button(Component.literal("X"), button -> this.completeAndOpenWorkspace());
        close.sizing(Sizing.fixed(18), Sizing.fixed(18));
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

    private FlowLayout actions() {
        Page page = PAGES.get(this.flow.pageIndex());
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent back = LumaUi.button(Component.translatable("luma.action.back"), button -> this.previousPage());
        back.active(!this.flow.firstPage());
        actions.child(back);

        ButtonComponent next;
        if (this.flow.lastPage()) {
            next = LumaUi.primaryButton(Component.translatable("luma.action.open_lumi"), button -> this.completeAndOpenWorkspace());
        } else {
            next = LumaUi.primaryButton(Component.translatable("luma.action.next"), button -> this.nextPage());
        }
        next.active(this.canAdvance(page));
        actions.child(next);
        return actions;
    }

    private void nextPage() {
        if (!this.canAdvance(PAGES.get(this.flow.pageIndex()))) {
            return;
        }
        this.flow = this.flow.next();
        this.resetHoldGate();
        this.rebuild();
    }

    private void previousPage() {
        this.flow = this.flow.previous();
        this.resetHoldGate();
        this.rebuild();
    }

    private void advanceAfterShortcut() {
        this.resetHoldGate();
        if (this.flow.lastPage()) {
            this.completeAndOpenWorkspace();
            return;
        }
        this.flow = this.flow.next();
        this.rebuild();
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

    private void resetHoldGate() {
        this.holdGate.reset();
        this.lastHoldSampleMillis = 0L;
    }

    private void completeAndOpenWorkspace() {
        this.onboardingService.markCompleted();
        this.router.openProjectSkippingOnboarding(this.parent, this.projectName, this.variantId, this.statusKey);
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private int dialogWidth() {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, this.width - 20));
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
