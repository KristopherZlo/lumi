package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused editor for the mutable tags attached to one saved version. */
public final class LumiVersionTagsScreen extends LumiModalScreen {
    private static final int DIALOG_HEIGHT = 124;
    private final Screen parent;
    private final VersionTags initialTags;
    private final Consumer<VersionTags> update;
    private LumiModalLayout layout;
    private EditBox tags;
    private String error = "";

    public LumiVersionTagsScreen(
            Screen parent, VersionTags initialTags, Consumer<VersionTags> update) {
        super(parent, Component.translatable("luma.action.edit_tags"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.initialTags = Objects.requireNonNull(initialTags, "initialTags");
        this.update = Objects.requireNonNull(update, "update");
    }

    @Override
    protected void init() {
        beginScreenInit();
        layout = fitPanel(width, height);
        int x = layout.x();
        int y = layout.y();
        tags = addTextField(
                x + 10, y + 59, layout.width() - 20,
                Component.translatable("luma.history.tags_input"));
        tags.setMaxLength(VersionTags.MAX_SERIALIZED_LENGTH);
        tags.setHint(Component.translatable("luma.history.tags_input"));
        tags.setValue(initialTags.serialize());

        int actionY = y + layout.height() - 28;
        addButton(x + 12, actionY, 100,
                Component.translatable("luma.action.save_tags"),
                this::submit, LumiButton.Kind.PRIMARY);
        addButton(x + 120, actionY, 80,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
    }

    @Override
    protected void setInitialFocus() {
        if (tags != null) {
            setInitialFocus(tags);
            tags.setFocused(true);
            tags.moveCursorToEnd(false);
        }
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
            VersionTags replacement = VersionTags.parse(tags.getValue());
            update.accept(replacement);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi could not update tags" : failed.getMessage();
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
            graphics.drawString(font,
                    Component.translatable("luma.history.tags_input"),
                    layout.x() + 12, layout.y() + 39,
                    LumiTheme.MUTED, false);
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

    static LumiModalLayout fitPanel(int screenWidth, int screenHeight) {
        return LumiModalLayout.fit(screenWidth, screenHeight, DIALOG_HEIGHT);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
