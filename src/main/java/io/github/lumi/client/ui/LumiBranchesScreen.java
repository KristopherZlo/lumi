package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Bounded native branch list with create, switch and merge actions. */
public final class LumiBranchesScreen extends LumiPageScreen {
    private static final int MAX_ROWS = 6;
    private static final int FORM_TOP = LumiTheme.PAGE_HEADER_HEIGHT + 8;
    private static final int FIELD_TOP = FORM_TOP + 14;
    private static final int LIST_TOP = FIELD_TOP + 28;
    private static final int INLINE_ACTION_WIDTH = 252;
    private static final int ICON_WIDTH = 26;
    private static final int STACKED_ICON_GAP = 4;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final Optional<HistorySnapshotPayload.ZoneView> activeZone;
    private final BranchNameController create;
    private final Consumer<String> merge;
    private final Consumer<String> openHistory;
    private final Consumer<String> switcher;
    private final Consumer<String> deleter;
    private final Consumer<HistorySnapshotPayload.Branch> bindSlot;
    private final Function<HistorySnapshotPayload.Branch, String> bindingLabel;
    private LumiModalLayout layout;
    private EditBox name;
    private LumiButton createButton;
    private int scroll;
    private int contentOffset;
    private String error = "";
    private HistorySnapshotPayload.Branch pendingDelete;

    public LumiBranchesScreen(
            Screen parent,
            Optional<HistorySnapshotPayload.ZoneView> activeZone,
            List<HistorySnapshotPayload.Branch> branches,
            BranchNameController create,
            Consumer<String> merge,
            Consumer<String> openHistory,
            Consumer<String> switcher,
            Consumer<String> deleter,
            Consumer<HistorySnapshotPayload.Branch> bindSlot,
            Function<HistorySnapshotPayload.Branch, String> bindingLabel) {
        super(parent, activeZone.isPresent()
                        ? Component.translatable(
                                "luma.screen.zone_ideas.title",
                                activeZone.orElseThrow().name())
                        : Component.translatable("luma.variants.overview_title"),
                ProjectTab.VARIANTS);
        this.activeZone = Objects.requireNonNull(activeZone, "activeZone");
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        this.create = Objects.requireNonNull(create, "create");
        this.merge = Objects.requireNonNull(merge, "merge");
        this.openHistory = Objects.requireNonNull(openHistory, "openHistory");
        this.switcher = Objects.requireNonNull(switcher, "switcher");
        this.deleter = Objects.requireNonNull(deleter, "deleter");
        this.bindSlot = Objects.requireNonNull(bindSlot, "bindSlot");
        this.bindingLabel = Objects.requireNonNull(bindingLabel, "bindingLabel");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiPageLayout shell = pageLayout();
        layout = new LumiModalLayout(
                shell.contentX(), shell.windowY(),
                shell.contentWidth(), shell.windowHeight());
        int x = layout.x();
        int y = layout.y();
        int contentWidth = Math.max(0, layout.width() - 32);
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.BRANCHES,
                x + 16, y + FORM_TOP, contentWidth);
        contentOffset = hintVisible ? contextualHintOffset(8) : 0;
        name = addTextField(
                x + 16, y + FIELD_TOP + contentOffset,
                Math.max(20, contentWidth - 106),
                Component.translatable("luma.variant.name_input"));
        name.setMaxLength(BranchNameController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.variant.name_input"));
        name.setResponder(value -> updateCreateButton());
        createButton = addButton(
                x + layout.width() - 116, y + FIELD_TOP + contentOffset, 100,
                Component.translatable("luma.action.variant_create"),
                this::createBranch,
                LumiButton.Kind.PRIMARY);
        updateCreateButton();

        if (pendingDelete != null) {
            addDeleteConfirmation(x, y, contentWidth);
            return;
        }

        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(scroll, branches.size());
        int end = Math.min(start + rows, branches.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Branch branch = branches.get(index);
            int rowY = y + LIST_TOP + contentOffset
                    + (index - start) * rowStride(layout.width());
            addBranchActions(branch, rowY);
        }
    }

    private void addBranchActions(
            HistorySnapshotPayload.Branch branch, int rowY) {
        int right = layout.x() + layout.width() - 20;
        boolean stacked = stacksActions(layout.width());
        int actionY = rowY + (stacked ? 24 : 4);
        int iconStride = stacked ? ICON_WIDTH + STACKED_ICON_GAP : 32;
        int switchX = stacked
                ? right - (ICON_WIDTH * 4 + STACKED_ICON_GAP * 3)
                : right - 122;
        int bindingRight = switchX - (stacked ? STACKED_ICON_GAP : 10);
        int bindingX = stacked
                ? Math.max(layout.x() + 20, bindingRight - 120)
                : right - INLINE_ACTION_WIDTH;
        int bindingWidth = Math.max(0, bindingRight - bindingX);
        if (bindingWidth >= 18) {
            addButton(bindingX, actionY, bindingWidth,
                    Component.literal(bindingLabel.apply(branch)),
                    () -> bindSlot.accept(branch), LumiButton.Kind.NORMAL);
        }
        LumiButton switchButton = addIconButton(
                    switchX, actionY, "rollback",
                    Component.translatable("luma.action.variant_switch"),
                    () -> switchBranch(branch.name()), LumiButton.Kind.PRIMARY);
        switchButton.active = !branch.active();
        addIconButton(switchX + iconStride, actionY, "folder",
                    Component.translatable("luma.action.open_history"),
                    () -> openHistory.accept(branch.name()),
                    LumiButton.Kind.NORMAL);
        LumiButton deleteButton = addIconButton(
                    switchX + iconStride * 2, actionY, "trash",
                    Component.translatable("luma.action.delete_branch"),
                    () -> confirmDelete(branch), LumiButton.Kind.DANGER);
        deleteButton.active = !branch.active();
        LumiButton mergeButton = addIconButton(
                    switchX + iconStride * 3, actionY, "merge",
                    Component.translatable("luma.action.merge_into_current"),
                    () -> merge.accept(branch.name()), LumiButton.Kind.NORMAL);
        mergeButton.active = !branch.active();
    }

    private void switchBranch(String name) {
        try {
            switcher.accept(name);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.variant_switched"), true);
            }
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi branch could not be switched" : failed.getMessage();
        }
    }

    private void createBranch() {
        BranchNameController.Submission submission = create.submit(name.getValue());
        error = submission.error();
        if (submission.accepted()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.variant_created"), true);
            }
            onClose();
        }
    }

    private void updateCreateButton() {
        if (createButton != null && name != null) {
            createButton.active = !name.getValue().trim().isEmpty();
        }
    }

    private void confirmDelete(HistorySnapshotPayload.Branch branch) {
        pendingDelete = branch;
        error = "";
        rebuildWidgets();
    }

    private void addDeleteConfirmation(int x, int y, int contentWidth) {
        int buttonWidth = Math.max(0, (contentWidth - 8) / 2);
        addButton(x + 16, y + LIST_TOP + 64 + contentOffset, buttonWidth,
                Component.translatable("luma.action.delete_branch"),
                this::deleteBranch, LumiButton.Kind.DANGER);
        addButton(x + 24 + buttonWidth,
                y + LIST_TOP + 64 + contentOffset, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    pendingDelete = null;
                    error = "";
                    rebuildWidgets();
                }, LumiButton.Kind.NORMAL);
    }

    private void deleteBranch() {
        try {
            deleter.accept(pendingDelete.name());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.variant_deleted"), true);
            }
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi branch could not be deleted" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderPage(graphics, layout.x(), layout.y(), layout.width(), layout.height());
        renderPageHeader(
                graphics, layout.x(), layout.y(), layout.width(), title,
                Component.translatable(activeZone.isPresent()
                        ? "luma.ideas.zone_overview_help"
                        : "luma.variants.overview_help"));
        graphics.drawString(font,
                Component.translatable("luma.variant.name_input"),
                layout.x() + 16,
                layout.y() + FORM_TOP + contentOffset,
                LumiTheme.MUTED, false);
        if (pendingDelete == null) {
            int rows = visibleRows();
            int start = rows == 0 ? 0 : Math.min(scroll, branches.size());
            int end = Math.min(start + rows, branches.size());
            for (int index = start; index < end; index++) {
                HistorySnapshotPayload.Branch branch = branches.get(index);
                int rowY = layout.y() + LIST_TOP + contentOffset
                        + (index - start) * rowStride(layout.width());
                renderPanel(graphics,
                        layout.x() + 16, rowY, layout.width() - 32,
                        rowHeight(layout.width()));
                int nameWidth = branchNameWidth(layout.width());
                if (nameWidth > 0) {
                    graphics.drawString(font,
                            font.plainSubstrByWidth(
                                    shortName(branch.name()), nameWidth),
                            layout.x() + 24, rowY + (stacksActions(layout.width()) ? 8 : 9),
                            branch.active()
                                    ? LumiTheme.ACCENT : LumiTheme.TEXT,
                            false);
                }
            }
            renderScrollbar(
                    graphics, layout.x() + 16,
                    layout.y() + LIST_TOP + contentOffset,
                    layout.width() - 20,
                    Math.max(0, layout.height() - LIST_TOP - contentOffset - 10),
                    branches.size(), rows, scroll, value -> scroll = value);
        } else {
            renderPanel(graphics, layout.x() + 16,
                    layout.y() + LIST_TOP + contentOffset,
                    layout.width() - 32, 54);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.action.delete_branch"),
                    layout.x() + layout.width() / 2,
                    layout.y() + LIST_TOP + 12 + contentOffset,
                    LumiTheme.DANGER);
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(
                            shortName(pendingDelete.name()), layout.width() - 64),
                    layout.x() + layout.width() / 2,
                    layout.y() + LIST_TOP + 32 + contentOffset,
                    LumiTheme.TEXT);
        }
        if (branches.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.merge.no_sources"),
                    layout.x() + layout.width() / 2,
                    layout.y() + LIST_TOP + 8 + contentOffset,
                    LumiTheme.MUTED);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    layout.x() + layout.width() / 2,
                    layout.y() + layout.height() - 44, LumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private int visibleRows() {
        return visibleRows(layout.height(), contentOffset, layout.width());
    }

    static int inlineBranchNameWidth(int layoutWidth) {
        return Math.max(0, layoutWidth - 20 - INLINE_ACTION_WIDTH - 6 - 24);
    }

    static boolean stacksActions(int layoutWidth) {
        return inlineBranchNameWidth(layoutWidth) < 60;
    }

    static int branchNameWidth(int layoutWidth) {
        return stacksActions(layoutWidth)
                ? Math.max(0, layoutWidth - 48)
                : inlineBranchNameWidth(layoutWidth);
    }

    static int rowHeight(int layoutWidth) {
        return stacksActions(layoutWidth) ? 46 : 26;
    }

    static int rowStride(int layoutWidth) {
        return stacksActions(layoutWidth) ? 50 : 30;
    }

    static int visibleRows(int layoutHeight, int contentOffset, int layoutWidth) {
        return Math.min(MAX_ROWS, Math.max(
                0, (layoutHeight - LIST_TOP - 10 - contentOffset)
                        / rowStride(layoutWidth)));
    }

    @Override
    protected void setInitialFocus() {
        if (name != null) {
            setInitialFocus(name);
            name.setFocused(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER)
                && createButton.active) {
            createBranch();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= layout.x() && x < layout.x() + layout.width()
                && y >= layout.y() + LIST_TOP + contentOffset
                && y < layout.y() + layout.height()) {
            int maximum = Math.max(0, branches.size() - visibleRows());
            int replacement = Math.max(0, Math.min(
                    maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) {
                scroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
}
