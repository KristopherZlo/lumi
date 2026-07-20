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

/** Import/Export form with integrated-world package browsing. */
public final class LumiPackageScreen extends LumiPageScreen {
    private static final int MAX_ROWS = 6;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_STRIDE = 28;
    private final PackageScreenController controller;
    private final PackageBrowserState browser;
    private final Consumer<String> switchBranch;
    private final Consumer<String> mergeBranch;
    private final Consumer<String> deleteBranch;
    private EditBox name;
    private LumiButton export;
    private LumiButton inspect;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private PackageGeometry geometry;
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
                ProjectTab.IMPORT_EXPORT);
        this.controller = Objects.requireNonNull(controller, "controller");
        browser = new PackageBrowserState(localAccess, branches);
        this.switchBranch = Objects.requireNonNull(switchBranch, "switchBranch");
        this.mergeBranch = Objects.requireNonNull(mergeBranch, "mergeBranch");
        this.deleteBranch = Objects.requireNonNull(deleteBranch, "deleteBranch");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiPageLayout layout = pageLayout();
        panelX = layout.contentX();
        panelY = layout.windowY();
        panelWidth = layout.contentWidth();
        panelHeight = layout.windowHeight();
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.IMPORT_EXPORT,
                panelX + 16, panelY + 48, panelWidth - 32);
        int hintHeight = hintVisible ? contextualHintOffset(0) : 0;
        geometry = packageGeometry(panelHeight, hintHeight);
        if (hintVisible) {
            moveContextualHint(panelX + 16, panelY + geometry.hintY());
        }
        loadLocalPackages();
        int x = panelX + 16;
        int contentWidth = panelWidth - 32;
        if (browser.pendingDelete().isPresent()) {
            addDeleteConfirmation(x, contentWidth);
            return;
        }
        if (!geometry.contentVisible()) {
            return;
        }
        name = addTextField(
                panelX + 14, panelY + geometry.fieldY(), panelWidth - 28,
                Component.translatable("luma.share.package_name"));
        name.setMaxLength(PackageScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.share.package_name"));
        name.setResponder(value -> updateActions());

        int actionWidth = Math.max(40, (contentWidth - 38) / 2);
        export = addButton(x, panelY + geometry.actionY(), actionWidth,
                Component.translatable("luma.action.export_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.EXPORT),
                LumiButton.Kind.PRIMARY);
        inspect = addButton(x + actionWidth + 6,
                panelY + geometry.actionY(), actionWidth,
                Component.translatable("luma.action.import_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.INSPECT),
                LumiButton.Kind.NORMAL);
        LumiButton folder = addIconButton(
                panelX + panelWidth - 42, panelY + geometry.actionY(), "folder",
                Component.translatable("luma.action.open_packages_folder"),
                this::openFolder, LumiButton.Kind.NORMAL);
        folder.active = browser.canOpenFolder();
        updateActions();
        addButton(x, panelY + geometry.optionY(), contentWidth,
                toggleLabel("luma.share.include_previews", includePreview),
                () -> {
                    includePreview = !includePreview;
                    rebuildWidgets();
                }, includePreview
                        ? LumiButton.Kind.SELECTED
                        : LumiButton.Kind.NORMAL);
        addTabs(x, contentWidth);
        addRows();
    }

    private void addTabs(int x, int width) {
        int tabWidth = Math.max(40, (width - 6) / 2);
        addButton(x, panelY + geometry.tabsY(), tabWidth,
                Component.translatable("luma.share.package_files_title"),
                () -> selectTab(false), browser.showImported()
                        ? LumiButton.Kind.NORMAL : LumiButton.Kind.SELECTED);
        addButton(x + tabWidth + 6,
                panelY + geometry.tabsY(), tabWidth,
                Component.translatable("luma.import_export.packages_title"),
                () -> selectTab(true), browser.showImported()
                        ? LumiButton.Kind.SELECTED : LumiButton.Kind.NORMAL);
    }

    private void addRows() {
        int rows = visibleRows();
        for (int index = browser.start(rows); index < browser.end(rows); index++) {
            int y = panelY + geometry.listY()
                    + (index - browser.start(rows)) * ROW_STRIDE;
            if (browser.showImported()) addImportedActions(browser.imported(index), y);
            else {
                var entry = browser.local(index);
                addButton(panelX + panelWidth - 100, y + 4, 84,
                        Component.translatable("luma.action.import_package"),
                        () -> submit(entry.name().value(),
                                PackageScreenController.Action.INSPECT),
                        LumiButton.Kind.NORMAL);
            }
        }
    }

    private void addImportedActions(HistorySnapshotPayload.Branch branch, int y) {
        if (branch.active()) return;
        addIconButton(panelX + panelWidth - 98, y + 3, "join",
                Component.translatable("luma.action.open_project"),
                () -> runBranchAction(switchBranch, branch.name(), true),
                LumiButton.Kind.PRIMARY);
        addIconButton(panelX + panelWidth - 70, y + 3, "branch",
                Component.translatable("luma.action.combine_with_build"),
                () -> runBranchAction(mergeBranch, branch.name(), false),
                LumiButton.Kind.NORMAL);
        addIconButton(panelX + panelWidth - 42, y + 3, "trash",
                Component.translatable("luma.action.delete_package"),
                () -> {
                    browser.confirmDelete(branch);
                    rebuildWidgets();
                }, LumiButton.Kind.DANGER);
    }

    private void addDeleteConfirmation(int x, int width) {
        int buttonWidth = Math.max(40, (width - 6) / 2);
        addButton(x, panelY + geometry.deleteActionY(), buttonWidth,
                Component.translatable("luma.action.delete_package"),
                () -> runBranchAction(deleteBranch,
                        browser.pendingDelete().orElseThrow().name(), true),
                LumiButton.Kind.DANGER);
        addButton(x + buttonWidth + 6,
                panelY + geometry.deleteActionY(), buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    browser.cancelDelete();
                    rebuildWidgets();
                }, LumiButton.Kind.NORMAL);
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
        return visibleRows(geometry);
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
                && export != null && export.active) {
            submit(name.getValue(), PackageScreenController.Action.EXPORT);
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
        int rows = visibleRows();
        int top = panelY + geometry.listY();
        if (browser.pendingDelete().isEmpty()
                && rows > 0
                && x >= panelX && x < panelX + panelWidth
                && y >= top && y < panelY + geometry.listBottom()) {
            int before = browser.start(rows);
            browser.scroll(verticalAmount < 0 ? 1 : -1, rows);
            if (browser.start(rows) != before) {
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderPageHeader(graphics, panelX, panelY, panelWidth, title,
                    geometry.compact() ? null
                            : Component.translatable("luma.simple.share_help"));
            if (browser.pendingDelete().isPresent()) renderDeleteConfirmation(graphics);
            else if (geometry.contentVisible()) {
                graphics.drawString(font,
                        Component.translatable("luma.share.package_name"),
                        panelX + 16, panelY + geometry.nameLabelY(),
                        LumiTheme.TEXT, false);
                renderRows(graphics);
            }
            if (!status.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(status, panelWidth - 32),
                        panelX + 16, panelY + geometry.statusY(),
                        failed ? LumiTheme.DANGER : LumiTheme.ACCENT,
                        false);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderRows(GuiGraphics graphics) {
        int rows = visibleRows();
        int start = browser.start(rows);
        int end = browser.end(rows);
        if (start == end) {
            if (geometry.listY() + 9 > geometry.listBottom()) return;
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            Component.translatable(browser.showImported()
                                    ? "luma.share.imported_empty"
                                    : "luma.share.package_files_empty").getString(),
                            Math.max(1, panelWidth - 40)),
                    panelX + 20, panelY + geometry.listY() + 6,
                    LumiTheme.MUTED, false);
            return;
        }
        for (int index = start; index < end; index++) {
            int y = panelY + geometry.listY()
                    + (index - start) * ROW_STRIDE;
            renderPanel(graphics, panelX + 16, y,
                    panelWidth - 32, ROW_HEIGHT);
            String label = browser.showImported()
                    ? shortName(browser.imported(index).name())
                    : browser.local(index).name().value() + ".lumi";
            graphics.drawString(font, font.plainSubstrByWidth(
                            label, rowTextWidth(panelWidth, browser.showImported())),
                    panelX + 24, y + 8, LumiTheme.TEXT, false);
        }
        renderScrollbar(
                graphics, panelX + 16,
                panelY + geometry.listY(),
                panelWidth - 28,
                Math.max(0, geometry.listBottom() - geometry.listY()),
                browser.size(), rows, start,
                value -> browser.scrollTo(value, rows));
    }

    private void renderDeleteConfirmation(GuiGraphics graphics) {
        renderPanel(graphics, panelX + 16,
                panelY + geometry.deleteY(),
                panelWidth - 32, geometry.deleteHeight());
        graphics.drawCenteredString(font,
                Component.translatable("luma.action.delete_package"),
                panelX + panelWidth / 2,
                panelY + geometry.deleteY() + 7,
                LumiTheme.DANGER);
        if (geometry.deleteHeight() >= 58) {
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(
                            shortName(browser.pendingDelete().orElseThrow().name()),
                            Math.max(1, panelWidth - 48)),
                    panelX + panelWidth / 2,
                    panelY + geometry.deleteY() + 22,
                    LumiTheme.TEXT);
        }
    }

    static PackageGeometry packageGeometry(int panelHeight, int hintHeight) {
        boolean compact = panelHeight < 260;
        int titleY = compact ? 8 : 16;
        int hintY = compact ? 22 : 52;
        int statusY = Math.max(0, panelHeight - 13);
        int contentShift = hintHeight == 0 ? 0
                : compact ? hintY + hintHeight + 5 - 20 : hintHeight + 8;
        int nameLabelY = (compact ? 20 : 52) + contentShift;
        int fieldY = (compact ? 29 : 60) + contentShift;
        int actionY = (compact ? 53 : 90) + contentShift;
        int optionY = (compact ? 75 : 118) + contentShift;
        int tabsY = (compact ? 97 : 144) + contentShift;
        int listY = (compact ? 119 : 174) + contentShift;
        int listBottom = Math.max(0, statusY - 4);
        boolean contentVisible = tabsY + 18 <= listBottom;
        int deleteY = hintHeight == 0
                ? (compact ? 28 : 60)
                : hintY + hintHeight + 5;
        int deleteHeight = Math.max(0, listBottom - deleteY);
        int deleteActionY = Math.max(deleteY, listBottom - 22);
        return new PackageGeometry(
                titleY, hintY, nameLabelY, fieldY, actionY, optionY,
                tabsY, listY, listBottom, statusY,
                deleteY, deleteHeight, deleteActionY,
                compact, contentVisible);
    }

    static int visibleRows(PackageGeometry geometry) {
        if (!geometry.contentVisible()) return 0;
        int available = geometry.listBottom() - geometry.listY();
        if (available < ROW_HEIGHT) return 0;
        return Math.min(MAX_ROWS,
                1 + (available - ROW_HEIGHT) / ROW_STRIDE);
    }

    static int rowTextWidth(int panelWidth, boolean imported) {
        int actionX = panelWidth - (imported ? 98 : 100);
        return Math.max(0, actionX - 24 - 6);
    }

    private static String shortName(String branch) {
        int prefix = branch.indexOf("import/");
        return prefix < 0 ? branch : branch.substring(prefix + 7);
    }

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ").append(
                Component.translatable(enabled ? "options.on" : "options.off"));
    }

    record PackageGeometry(
            int titleY,
            int hintY,
            int nameLabelY,
            int fieldY,
            int actionY,
            int optionY,
            int tabsY,
            int listY,
            int listBottom,
            int statusY,
            int deleteY,
            int deleteHeight,
            int deleteActionY,
            boolean compact,
            boolean contentVisible) {
    }

    @Override public boolean isPauseScreen() { return false; }
}
