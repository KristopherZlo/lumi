package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.domain.model.VersionDisplayName;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused editor for one saved version's display name. */
final class LumiVersionRenameScreen extends LumiModalScreen {
    private static final int DIALOG_HEIGHT = 124;
    private final Screen parent;
    private final String initialName;
    private final Consumer<String> rename;
    private LumiModalLayout layout;
    private LumiTextField name;
    private String error = "";

    LumiVersionRenameScreen(
            Screen parent, String initialName, Consumer<String> rename) {
        super(parent, Component.translatable("luma.save_details.rename_title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.initialName = Objects.requireNonNull(initialName, "initialName");
        this.rename = Objects.requireNonNull(rename, "rename");
    }

    @Override
    protected void init() {
        beginScreenInit();
        layout = LumiModalLayout.fit(width, height, DIALOG_HEIGHT);
        int x = layout.x();
        int y = layout.y();
        name = addTextField(x + 10, y + 59, layout.width() - 20,
                Component.translatable("luma.save_details.rename_title"));
        name.setMaxLength(VersionDisplayName.MAX_LENGTH);
        name.setValue(initialName);
        int actionY = y + layout.height() - 28;
        addButton(x + 12, actionY, 100,
                Component.translatable("luma.action.save"),
                this::submit, LumiButton.Kind.PRIMARY);
        addButton(x + 120, actionY, 80,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(name);
        name.setFocused(true);
        name.moveCursorToEnd(false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        try {
            VersionDisplayName replacement =
                    new VersionDisplayName(name.getValue());
            rename.accept(replacement.value());
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi could not rename this Save" : failed.getMessage();
        }
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderWindow(
                    graphics, layout.x(), layout.y(), layout.width(), layout.height());
            graphics.drawString(font, title, layout.x() + 12, layout.y() + 14,
                    LumiTheme.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(
                            Component.translatable("luma.save_details.rename_help")
                                    .getString(), layout.width() - 24),
                    layout.x() + 12, layout.y() + 39, LumiTheme.MUTED, false);
            if (!error.isEmpty()) {
                graphics.drawString(font, errorText(error),
                        layout.x() + 12, layout.y() + 82,
                        LumiTheme.DANGER, false);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
