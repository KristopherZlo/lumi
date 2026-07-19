package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.ClientPackageAccess;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Legacy Import/Export form with integrated-world package browsing. */
public final class LumiPackageScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 6;
    private final PackageScreenController controller;
    private final PackageBrowserState browser;
    private EditBox name;
    private LumiLegacyButton export;
    private LumiLegacyButton inspect;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private String status = "";
    private boolean failed;

    public LumiPackageScreen(
            Screen parent,
            PackageScreenController controller,
            Optional<ClientPackageAccess> localAccess) {
        super(parent, Component.translatable("luma.screen.import_export.title"),
                LegacyProjectTab.IMPORT_EXPORT);
        this.controller = Objects.requireNonNull(controller, "controller");
        browser = new PackageBrowserState(
                Objects.requireNonNull(localAccess, "localAccess"), List.of());
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout layout = pageLayout();
        panelX = layout.contentX();
        panelY = layout.windowY();
        panelWidth = layout.contentWidth();
        panelHeight = layout.windowHeight();
        loadLocalPackages();
        int x = panelX + 16;
        int contentWidth = panelWidth - 32;
        name = new EditBox(font, x, panelY + 62, contentWidth, 20,
                Component.translatable("luma.share.package_name"));
        name.setMaxLength(PackageScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.share.package_name"));
        name.setBordered(false);
        name.setTextColor(LegacyLumiTheme.TEXT);
        name.setResponder(value -> updateActions());
        addRenderableWidget(name);

        int actionWidth = Math.max(40, (contentWidth - 38) / 2);
        export = addLegacyButton(x, panelY + 90, actionWidth,
                Component.translatable("luma.action.export_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.EXPORT),
                LumiLegacyButton.Kind.PRIMARY);
        inspect = addLegacyButton(x + actionWidth + 6, panelY + 90, actionWidth,
                Component.translatable("luma.action.import_package"),
                () -> submit(name.getValue(), PackageScreenController.Action.INSPECT),
                LumiLegacyButton.Kind.NORMAL);
        LumiLegacyButton folder = addLegacyIconButton(
                panelX + panelWidth - 42, panelY + 90, "folder",
                Component.translatable("luma.action.open_packages_folder"),
                this::openFolder, LumiLegacyButton.Kind.NORMAL);
        folder.active = browser.canOpenFolder();
        addLegacyIconButton(panelX + panelWidth - 42, panelY + 12, "close",
                Component.translatable("luma.action.close"), this::onClose,
                LumiLegacyButton.Kind.NORMAL);
        updateActions();
        addPackageRows();
        addPaging(x);
    }

    private void addPackageRows() {
        int rows = visibleRows();
        for (int index = browser.start(rows); index < browser.end(rows); index++) {
            var entry = browser.local(index);
            int y = panelY + 132 + (index - browser.start(rows)) * 28;
            addLegacyButton(panelX + panelWidth - 100, y + 4, 84,
                    Component.translatable("luma.action.import_package"),
                    () -> submit(entry.name().value(),
                            PackageScreenController.Action.INSPECT),
                    LumiLegacyButton.Kind.NORMAL);
        }
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
        var result = controller.submit(value, action);
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

    private void updateActions() {
        if (export != null && inspect != null && name != null) {
            export.active = inspect.active = !name.getValue().trim().isEmpty();
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, Math.max(1, (panelHeight - 152) / 28));
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
                    panelX + 16, panelY + 52, LegacyLumiTheme.TEXT, false);
            LegacyLumiTheme.outlined(graphics, panelX + 14, panelY + 60,
                    panelWidth - 28, 24,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            renderPackageRows(graphics);
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

    private void renderPackageRows(GuiGraphics graphics) {
        int rows = visibleRows();
        int start = browser.start(rows);
        int end = browser.end(rows);
        if (start == end) {
            graphics.drawString(font,
                    Component.translatable("luma.share.package_files_empty"),
                    panelX + 20, panelY + 138, LegacyLumiTheme.MUTED, false);
            return;
        }
        for (int index = start; index < end; index++) {
            int y = panelY + 132 + (index - start) * 28;
            renderLegacyPanel(graphics, panelX + 16, y, panelWidth - 32, 24);
            graphics.drawString(font, font.plainSubstrByWidth(
                            browser.local(index).name().value() + ".lumi",
                            Math.max(0, panelWidth - 110)),
                    panelX + 24, y + 8, LegacyLumiTheme.TEXT, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
