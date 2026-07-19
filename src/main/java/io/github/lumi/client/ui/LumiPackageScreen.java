package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.ClientPackageAccess;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.network.HistorySnapshotPayload;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Legacy Import/Export form with integrated-world package browsing. */
public final class LumiPackageScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 6;
    private static final int OPTIONS_HEIGHT = 26;
    private final PackageScreenController controller;
    private final PackageBrowserState browser;
    private final Consumer<String> switchBranch;
    private final Consumer<String> mergeBranch;
    private final Consumer<String> deleteBranch;
    private EditBox name;
    private LumiLegacyButton export;
    private LumiLegacyButton inspect;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentOffset;
    private String status = "";
    private boolean failed;
    private boolean includePreview;

    public LumiPackageScreen(
            Screen parent,
            PackageScreenController controller,
            Optional<ClientPackageAccess> localAccess,
            List<HistorySnapshotPayload.Branch> branches,
            Consumer<String> switchBranch,
            Consumer<String> mergeBranch,
            Consumer<String> deleteBranch) {
        super(parent, Component.translatable("luma.screen.import_export.title"),
                LegacyProjectTab.IMPORT_EXPORT);
        this.controller = Objects.requireNonNull(controller, "controller");
        browser = new PackageBrowserState(localAccess, branches);
        this.switchBranch = Objects.requireNonNull(switchBranch, "switchBranch");
        this.mergeBranch = Objects.requireNonNull(mergeBranch, "mergeBranch");
        this.deleteBranch = Objects.requireNonNull(deleteBranch, "deleteBranch");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout layout = pageLayout();
        panelX = layout.contentX();
        panelY = layout.windowY();
        panelWidth = layout.contentWidth();
        panelHeight = layout.windowHeight();
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.IMPORT_EXPORT,
                panelX + 16, panelY + 48, panelWidth - 32);
        contentOffset = hintVisible ? contextualHintOffset(8) : 0;
        loadLocalPackages();
        int x = panelX + 16;
        int contentWidth = panelWidth - 32;
        name = new EditBox(font, x, panelY + 63 + contentOffset, contentWidth, 16,
                Component.translatable("luma.share.package_name"));
        name.setMaxLength(PackageScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.share.package_name"));
        name.setBordered(false);
        name.setTextColor(LegacyLumiTheme.TEXT);
        name.setResponder(value -> updateActions());
        addRenderableWidget(name);

        int actionWidth = Math.max(40, (contentWidth - 38) / 2);
        export = addLegacyButton(x, panelY + 90 + contentOffset, actionWidth,
                Component.translatable("luma.action.export_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.EXPORT),
                LumiLegacyButton.Kind.PRIMARY);
        inspect = addLegacyButton(x + actionWidth + 6,
                panelY + 90 + contentOffset, actionWidth,
                Component.translatable("luma.action.import_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.INSPECT),
                LumiLegacyButton.Kind.NORMAL);
        LumiLegacyButton folder = addLegacyIconButton(
                panelX + panelWidth - 42, panelY + 90 + contentOffset, "folder",
                Component.translatable("luma.action.open_packages_folder"),
                this::openFolder, LumiLegacyButton.Kind.NORMAL);
        folder.active = browser.canOpenFolder();
        addLegacyIconButton(panelX + panelWidth - 42, panelY + 12, "close",
                Component.translatable("luma.action.close"), this::onClose,
                LumiLegacyButton.Kind.NORMAL);
        updateActions();
        addLegacyButton(x, panelY + 118 + contentOffset, contentWidth,
                toggleLabel("luma.share.include_previews", includePreview),
                () -> {
                    includePreview = !includePreview;
                    rebuildWidgets();
                }, includePreview
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addTabs(x, contentWidth);
        if (browser.pendingDelete().isPresent()) addDeleteConfirmation(x, contentWidth);
        else addRows();
        addPaging(x);
    }

    private void addTabs(int x, int width) {
        int tabWidth = Math.max(40, (width - 6) / 2);
        addLegacyButton(x, panelY + 118 + contentOffset + OPTIONS_HEIGHT, tabWidth,
                Component.translatable("luma.share.package_files_title"),
                () -> selectTab(false), browser.showImported()
                        ? LumiLegacyButton.Kind.NORMAL : LumiLegacyButton.Kind.SELECTED);
        addLegacyButton(x + tabWidth + 6,
                panelY + 118 + contentOffset + OPTIONS_HEIGHT, tabWidth,
                Component.translatable("luma.import_export.packages_title"),
                () -> selectTab(true), browser.showImported()
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
    }

    private void addRows() {
        int rows = visibleRows();
        for (int index = browser.start(rows); index < browser.end(rows); index++) {
            int y = panelY + 148 + contentOffset + OPTIONS_HEIGHT
                    + (index - browser.start(rows)) * 28;
            if (browser.showImported()) addImportedActions(browser.imported(index), y);
            else {
                var entry = browser.local(index);
                addLegacyButton(panelX + panelWidth - 100, y + 4, 84,
                        Component.translatable("luma.action.import_package"),
                        () -> submit(entry.name().value(),
                                PackageScreenController.Action.INSPECT),
                        LumiLegacyButton.Kind.NORMAL);
            }
        }
    }

    private void addImportedActions(HistorySnapshotPayload.Branch branch, int y) {
        if (branch.active()) return;
        addLegacyIconButton(panelX + panelWidth - 98, y + 3, "join",
                Component.translatable("luma.action.open_project"),
                () -> runBranchAction(switchBranch, branch.name(), true),
                LumiLegacyButton.Kind.PRIMARY);
        addLegacyIconButton(panelX + panelWidth - 70, y + 3, "branch",
                Component.translatable("luma.action.combine_with_build"),
                () -> runBranchAction(mergeBranch, branch.name(), false),
                LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + panelWidth - 42, y + 3, "trash",
                Component.translatable("luma.action.delete_package"),
                () -> {
                    browser.confirmDelete(branch);
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.DANGER);
    }

    private void addDeleteConfirmation(int x, int width) {
        int buttonWidth = Math.max(40, (width - 6) / 2);
        addLegacyButton(x, panelY + 178 + contentOffset + OPTIONS_HEIGHT, buttonWidth,
                Component.translatable("luma.action.delete_package"),
                () -> runBranchAction(deleteBranch,
                        browser.pendingDelete().orElseThrow().name(), true),
                LumiLegacyButton.Kind.DANGER);
        addLegacyButton(x + buttonWidth + 6,
                panelY + 178 + contentOffset + OPTIONS_HEIGHT, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    browser.cancelDelete();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
    }

    private void addPaging(int x) {
        int rows = visibleRows();
        int y = panelY + panelHeight - 26;
        LumiLegacyButton previous = addLegacyIconButton(
                x, y, "chevron-left", Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = browser.hasPrevious();
        LumiLegacyButton next = addLegacyIconButton(
                x + 28, y, "chevron-right", Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = browser.hasNext(rows);
    }

    private void submit(String value, PackageScreenController.Action action) {
        var result = controller.submit(
                value, action, action == PackageScreenController.Action.EXPORT
                        && includePreview);
        failed = !result.accepted();
        status = result.accepted() ? (action == PackageScreenController.Action.EXPORT
                ? "Export started" : "Inspecting package") : result.error();
    }

    private void loadLocalPackages() {
        try {
            browser.refreshLocal();
        } catch (IOException failure) {
            failed = true;
            status = failure.getMessage();
        }
    }

    private void openFolder() {
        try {
            browser.openFolder();
            failed = false;
            status = Component.translatable(
                    "luma.status.package_folder_opened").getString();
        } catch (IOException | RuntimeException failure) {
            failed = true;
            status = failure.getMessage() == null
                    ? "Lumi package folder could not be opened"
                    : failure.getMessage();
        }
    }

    private void changePage(int delta) {
        browser.changePage(delta);
        rebuildWidgets();
    }

    private void selectTab(boolean imported) {
        browser.selectTab(imported);
        rebuildWidgets();
    }

    private void runBranchAction(
            Consumer<String> action, String branch, boolean close) {
        try {
            action.accept(branch);
            if (close) onClose();
        } catch (RuntimeException failure) {
            failed = true;
            status = failure.getMessage() == null
                    ? "Lumi imported package action failed" : failure.getMessage();
        }
    }

    private void updateActions() {
        if (export != null && inspect != null && name != null) {
            export.active = inspect.active = !name.getValue().trim().isEmpty();
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS,
                Math.max(1, (panelHeight - 168 - contentOffset
                        - OPTIONS_HEIGHT) / 28));
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(name);
        name.setFocused(true);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) && export.active) {
            submit(name.getValue(), PackageScreenController.Action.EXPORT);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawString(font, title, panelX + 16, panelY + 16,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, Component.translatable("luma.simple.share_help"),
                    panelX + 16, panelY + 38, LegacyLumiTheme.MUTED, false);
            graphics.drawString(font, Component.translatable("luma.share.package_name"),
                    panelX + 16, panelY + 52 + contentOffset,
                    LegacyLumiTheme.TEXT, false);
            LegacyLumiTheme.outlined(graphics, panelX + 14,
                    panelY + 60 + contentOffset,
                    panelWidth - 28, 20,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            if (browser.pendingDelete().isPresent()) renderDeleteConfirmation(graphics);
            else renderRows(graphics);
            if (!status.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(status, panelWidth - 94),
                        panelX + 80, panelY + panelHeight - 20,
                        failed ? LegacyLumiTheme.DANGER : LegacyLumiTheme.ACCENT,
                        false);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderRows(GuiGraphics graphics) {
        int rows = visibleRows();
        int start = browser.start(rows);
        int end = browser.end(rows);
        if (start == end) {
            graphics.drawString(font,
                    Component.translatable(browser.showImported()
                            ? "luma.share.imported_empty"
                            : "luma.share.package_files_empty"),
                    panelX + 20, panelY + 154 + contentOffset + OPTIONS_HEIGHT,
                    LegacyLumiTheme.MUTED, false);
            return;
        }
        for (int index = start; index < end; index++) {
            int y = panelY + 148 + contentOffset + OPTIONS_HEIGHT
                    + (index - start) * 28;
            renderLegacyPanel(graphics, panelX + 16, y, panelWidth - 32, 24);
            String label = browser.showImported()
                    ? shortName(browser.imported(index).name())
                    : browser.local(index).name().value() + ".lumi";
            graphics.drawString(font, font.plainSubstrByWidth(label,
                            Math.max(0, panelWidth - (browser.showImported() ? 100 : 110))),
                    panelX + 24, y + 8, LegacyLumiTheme.TEXT, false);
        }
    }

    private void renderDeleteConfirmation(GuiGraphics graphics) {
        renderLegacyPanel(graphics, panelX + 16,
                panelY + 148 + contentOffset + OPTIONS_HEIGHT,
                panelWidth - 32, 48);
        graphics.drawCenteredString(font,
                Component.translatable("luma.action.delete_package"),
                panelX + panelWidth / 2,
                panelY + 158 + contentOffset + OPTIONS_HEIGHT,
                LegacyLumiTheme.DANGER);
        graphics.drawCenteredString(font,
                shortName(browser.pendingDelete().orElseThrow().name()),
                panelX + panelWidth / 2,
                panelY + 174 + contentOffset + OPTIONS_HEIGHT,
                LegacyLumiTheme.TEXT);
    }

    private static String shortName(String branch) {
        int prefix = branch.indexOf("import/");
        return prefix < 0 ? branch : branch.substring(prefix + 7);
    }

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ").append(
                Component.translatable(enabled ? "options.on" : "options.off"));
    }

    @Override public boolean isPauseScreen() { return false; }
}
