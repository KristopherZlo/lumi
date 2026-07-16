package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** One logical-name form for world-local package export and trust inspection. */
public final class LumiPackageScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 174;
    private final Screen parent;
    private final PackageScreenController controller;
    private EditBox name;
    private Button export;
    private Button inspect;
    private String status = "";
    private boolean failed;
    private int panelX;
    private int panelY;

    public LumiPackageScreen(Screen parent, PackageScreenController controller) {
        super(Component.translatable("luma.screen.import_export.title"));
        this.parent = parent;
        this.controller = controller;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(font, contentX, panelY + 68, contentWidth, 20,
                Component.translatable("luma.share.package_name"));
        name.setMaxLength(PackageScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.share.package_name"));
        name.setResponder(value -> {
            boolean active = !value.trim().isEmpty();
            export.active = active;
            inspect.active = active;
        });
        addRenderableWidget(name);

        int buttonWidth = (contentWidth - 16) / 3;
        export = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.export_package"),
                ignored -> submit(PackageScreenController.Action.EXPORT))
                .bounds(contentX, panelY + 126, buttonWidth, 20).build());
        inspect = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.import_package"),
                ignored -> submit(PackageScreenController.Action.INSPECT))
                .bounds(contentX + buttonWidth + 8,
                        panelY + 126, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(contentX + (buttonWidth + 8) * 2,
                        panelY + 126, buttonWidth, 20).build());
        export.active = false;
        inspect.active = false;
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
            submit(PackageScreenController.Action.EXPORT);
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit(PackageScreenController.Action action) {
        var result = controller.submit(name.getValue(), action);
        failed = !result.accepted();
        status = result.accepted()
                ? (action == PackageScreenController.Action.EXPORT
                        ? "Export started" : "Inspecting package")
                : result.error();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 17, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.simple.share_help"),
                width / 2, panelY + 37, 0xffaeb6c2);
        graphics.drawString(font, Component.translatable("luma.share.package_name"),
                panelX + 20, panelY + 55, 0xffdbe2ea, false);
        if (!status.isEmpty()) {
            graphics.drawString(font, Component.literal(status),
                    panelX + 20, panelY + 97,
                    failed ? 0xffff6b6b : 0xff8fca9a, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
