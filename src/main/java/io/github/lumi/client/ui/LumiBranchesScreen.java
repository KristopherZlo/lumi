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

/** Bounded native branch list with the legacy create, switch and merge actions. */
public final class LumiBranchesScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 6;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final Optional<HistorySnapshotPayload.ZoneView> activeZone;
    private final BranchNameController create;
    private final Consumer<String> merge;
    private final Consumer<String> openHistory;
    private final Consumer<String> switcher;
    private final Consumer<String> deleter;
    private final Consumer<HistorySnapshotPayload.Branch> bindSlot;
    private final Function<HistorySnapshotPayload.Branch, String> bindingLabel;
    private LegacyModalLayout layout;
    private EditBox name;
    private LumiLegacyButton createButton;
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
                LegacyProjectTab.VARIANTS);
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
        beginLegacyInit();
        LegacyWorkspaceLayout shell = pageLayout();
        layout = new LegacyModalLayout(
                shell.contentX(), shell.windowY(),
                shell.contentWidth(), shell.windowHeight());
        int x = layout.x();
        int y = layout.y();
        int contentWidth = Math.max(0, layout.width() - 32);
        int zoneOffset = activeZone.isPresent() ? 18 : 0;
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.BRANCHES,
                x + 16, y + 36 + zoneOffset, contentWidth);
        contentOffset = zoneOffset
                + (hintVisible ? contextualHintOffset(8) : 0);
        name = new EditBox(
                font, x + 18, y + 41 + contentOffset,
                Math.max(20, contentWidth - 120), 16,
                Component.translatable("luma.variant.name_input"));
        name.setMaxLength(BranchNameController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.variant.name_input"));
        name.setBordered(false);
        name.setResponder(value -> updateCreateButton());
        addRenderableWidget(name);
        createButton = addLegacyButton(
                x + layout.width() - 116, y + 40 + contentOffset, 100,
                Component.translatable("luma.action.variant_create"),
                this::createBranch,
                LumiLegacyButton.Kind.PRIMARY);
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
            int rowY = y + 70 + contentOffset + (index - start) * 30;
            int right = x + layout.width() - 20;
            addLegacyButton(right - 252, rowY + 4, 120,
                    Component.literal(bindingLabel.apply(branch)),
                    () -> bindSlot.accept(branch), LumiLegacyButton.Kind.NORMAL);
            LumiLegacyButton switchButton = addLegacyIconButton(
                    right - 122, rowY + 4, "rollback",
                    Component.translatable("luma.action.variant_switch"),
                    () -> switchBranch(branch.name()), LumiLegacyButton.Kind.PRIMARY);
            switchButton.active = !branch.active();
            addLegacyIconButton(right - 90, rowY + 4, "folder",
                    Component.translatable("luma.action.open_history"),
                    () -> openHistory.accept(branch.name()),
                    LumiLegacyButton.Kind.NORMAL);
            LumiLegacyButton deleteButton = addLegacyIconButton(
                    right - 58, rowY + 4, "trash",
                    Component.translatable("luma.action.delete_branch"),
                    () -> confirmDelete(branch), LumiLegacyButton.Kind.DANGER);
            deleteButton.active = !branch.active();
            LumiLegacyButton mergeButton = addLegacyIconButton(
                    right - 26, rowY + 4, "merge",
                    Component.translatable("luma.action.merge_into_current"),
                    () -> merge.accept(branch.name()), LumiLegacyButton.Kind.NORMAL);
            mergeButton.active = !branch.active();
        }
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
        addLegacyButton(x + 16, y + 142 + contentOffset, buttonWidth,
                Component.translatable("luma.action.delete_branch"),
                this::deleteBranch, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(x + 24 + buttonWidth, y + 142 + contentOffset, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    pendingDelete = null;
                    error = "";
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
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
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, layout.x(), layout.y(), layout.width(), layout.height());
        graphics.drawString(font, title, layout.x() + 16, layout.y() + 14,
                LegacyLumiTheme.TEXT, false);
        activeZone.ifPresent(zone -> graphics.drawString(
                font,
                Component.translatable("luma.ideas.zone_badge", zone.name()),
                layout.x() + 16, layout.y() + 32, zone.color(), false));
        LegacyLumiTheme.outlined(
                graphics, name.getX() - 2, name.getY() - 2,
                name.getWidth() + 4, name.getHeight() + 4,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        if (pendingDelete == null) {
            int rows = visibleRows();
            int start = rows == 0 ? 0 : Math.min(scroll, branches.size());
            int end = Math.min(start + rows, branches.size());
            for (int index = start; index < end; index++) {
                HistorySnapshotPayload.Branch branch = branches.get(index);
                int rowY = layout.y() + 70 + contentOffset + (index - start) * 30;
                renderLegacyPanel(graphics,
                        layout.x() + 16, rowY, layout.width() - 32, 26);
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                shortName(branch.name()), layout.width() - 114),
                        layout.x() + 24, rowY + 9,
                        branch.active() ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.TEXT,
                        false);
            }
        } else {
            renderLegacyPanel(graphics, layout.x() + 16,
                    layout.y() + 76 + contentOffset,
                    layout.width() - 32, 54);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.action.delete_branch"),
                    layout.x() + layout.width() / 2,
                    layout.y() + 88 + contentOffset, LegacyLumiTheme.DANGER);
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(
                            shortName(pendingDelete.name()), layout.width() - 64),
                    layout.x() + layout.width() / 2,
                    layout.y() + 108 + contentOffset, LegacyLumiTheme.TEXT);
        }
        if (branches.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.merge.no_sources"),
                    layout.x() + layout.width() / 2,
                    layout.y() + 86 + contentOffset, LegacyLumiTheme.MUTED);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    layout.x() + layout.width() / 2,
                    layout.y() + layout.height() - 44, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS,
                Math.max(0, (layout.height() - 100 - contentOffset) / 30));
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
                && y >= layout.y() + 70 + contentOffset
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
