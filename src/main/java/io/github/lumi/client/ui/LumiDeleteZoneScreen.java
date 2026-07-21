package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Typed-name danger confirmation for deleting zone metadata only. */
public final class LumiDeleteZoneScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 190;
    private final Screen parent;
    private final HistorySnapshotPayload.ZoneView zone;
    private final BiConsumer<UUID, Long> delete;
    private EditBox confirmation;
    private LumiButton submit;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private String error = "";

    public LumiDeleteZoneScreen(
            Screen parent,
            HistorySnapshotPayload.ZoneView zone,
            BiConsumer<UUID, Long> delete) {
        super(Component.translatable("luma.zones.delete_title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        confirmation = addTextField(
                panelX + 20, panelY + 80, panelWidth - 40,
                Component.translatable("luma.zones.delete_input"));
        confirmation.setMaxLength(256);
        confirmation.setHint(Component.translatable("luma.zones.delete_input"));
        confirmation.setResponder(ignored -> updateSubmit());
        int buttonWidth = (panelWidth - 48) / 2;
        submit = addButton(
                panelX + 20, panelY + actionOffset(panelHeight), buttonWidth,
                Component.translatable("luma.zones.delete_confirm"),
                this::delete, LumiButton.Kind.DANGER);
        addButton(
                panelX + 28 + buttonWidth,
                panelY + actionOffset(panelHeight), buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
        updateSubmit();
    }

    private void updateSubmit() {
        if (submit != null) {
            submit.active = confirmation != null
                    && zone.name().equals(confirmation.getValue());
        }
    }

    private void delete() {
        if (!zone.name().equals(confirmation.getValue())) {
            error = "luma.status.zone_delete_name_mismatch";
            return;
        }
        try {
            delete.accept(zone.id(), zone.revision());
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi zone could not be deleted" : failed.getMessage();
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
            int contentLeft = panelX + 20;
            int contentRight = panelX + panelWidth - 20;
            renderWindow(
                    graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            title, centerX, contentLeft, contentRight),
                    centerX, panelY + 18,
                    LumiTheme.DANGER);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            Component.translatable(
                                    "luma.zones.delete_help", zone.name()),
                            centerX, contentLeft, contentRight),
                    centerX, panelY + 46, LumiTheme.MUTED);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(
                        font, clippedCenteredHeader(
                                errorText(error), centerX,
                                contentLeft, contentRight),
                        centerX, panelY + 118,
                        LumiTheme.DANGER);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    static LumiModalLayout fitPanel(int screenWidth, int screenHeight) {
        int width = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 32));
        int height = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LumiModalLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2), width, height);
    }

    static int actionOffset(int panelHeight) {
        return Math.max(0, Math.min(142, panelHeight - 22));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
