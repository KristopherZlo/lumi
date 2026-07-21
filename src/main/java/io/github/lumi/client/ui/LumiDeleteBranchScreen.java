package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Typed-name danger confirmation for deleting one inactive branch. */
public final class LumiDeleteBranchScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 190;
    private final Screen parent;
    private final HistorySnapshotPayload.Branch branch;
    private final Consumer<String> delete;
    private final Runnable deleted;
    private EditBox confirmation;
    private LumiButton submit;
    private LumiModalLayout layout;
    private String error = "";

    public LumiDeleteBranchScreen(
            Screen parent,
            HistorySnapshotPayload.Branch branch,
            Consumer<String> delete,
            Runnable deleted) {
        super(Component.translatable(
                "luma.ideas.delete_confirm_title", shortName(branch.name())));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.branch = Objects.requireNonNull(branch, "branch");
        this.delete = Objects.requireNonNull(delete, "delete");
        this.deleted = Objects.requireNonNull(deleted, "deleted");
    }

    @Override
    protected void init() {
        beginScreenInit();
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, width - 32));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, height - 16));
        layout = new LumiModalLayout(
                Math.max(0, (width - panelWidth) / 2),
                Math.max(0, (height - panelHeight) / 2),
                panelWidth, panelHeight);
        confirmation = addTextField(
                layout.x() + 20, layout.y() + 80, layout.width() - 40,
                Component.translatable("luma.ideas.delete_confirm_input_help"));
        confirmation.setMaxLength(256);
        confirmation.setHint(Component.translatable(
                "luma.ideas.delete_confirm_input_help"));
        confirmation.setResponder(ignored -> updateSubmit());
        int buttonWidth = Math.max(1, (layout.width() - 48) / 2);
        int actionY = layout.y() + Math.max(0, layout.height() - 48);
        submit = addButton(
                layout.x() + 20, actionY, buttonWidth,
                Component.translatable("luma.action.delete_branch"),
                this::delete, LumiButton.Kind.DANGER);
        addButton(
                layout.x() + 28 + buttonWidth, actionY, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
        updateSubmit();
    }

    private void updateSubmit() {
        if (submit != null) {
            submit.active = confirmation != null
                    && shortName(branch.name()).equals(confirmation.getValue());
        }
    }

    private void delete() {
        if (!shortName(branch.name()).equals(confirmation.getValue())) return;
        try {
            delete.accept(branch.name());
            deleted.run();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi branch could not be deleted" : failed.getMessage();
        }
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(confirmation);
        confirmation.setFocused(true);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER)
                && submit.active) {
            delete();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(
                graphics, mouseX, mouseY);
        try {
            int centerX = width / 2;
            int contentLeft = layout.x() + 20;
            int contentRight = layout.x() + layout.width() - 20;
            renderWindow(graphics, layout.x(), layout.y(),
                    layout.width(), layout.height());
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    title, centerX, contentLeft, contentRight),
                    centerX, layout.y() + 18, LumiTheme.DANGER);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.ideas.delete_confirm_help",
                            shortName(branch.name())),
                    centerX, contentLeft, contentRight),
                    centerX, layout.y() + 46, LumiTheme.MUTED);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(font, clippedCenteredHeader(
                        errorText(error), centerX, contentLeft, contentRight),
                        centerX, layout.y() + 118, LumiTheme.DANGER);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private static String shortName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
