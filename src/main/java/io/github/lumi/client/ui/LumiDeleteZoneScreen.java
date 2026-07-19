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
public final class LumiDeleteZoneScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 190;
    private final Screen parent;
    private final HistorySnapshotPayload.ZoneView zone;
    private final BiConsumer<UUID, Long> delete;
    private EditBox confirmation;
    private LumiLegacyButton submit;
    private int panelX;
    private int panelY;
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
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        confirmation = new EditBox(
                font, panelX + 22, panelY + 83, panelWidth - 44, 16,
                Component.translatable("luma.zones.delete_input"));
        confirmation.setMaxLength(256);
        confirmation.setHint(Component.translatable("luma.zones.delete_input"));
        confirmation.setBordered(false);
        confirmation.setResponder(ignored -> updateSubmit());
        addRenderableWidget(confirmation);
        int buttonWidth = (panelWidth - 48) / 2;
        submit = addLegacyButton(
                panelX + 20, panelY + 142, buttonWidth,
                Component.translatable("luma.zones.delete_confirm"),
                this::delete, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(
                panelX + 28 + buttonWidth, panelY + 142, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
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
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.zone_deleted"), true);
            }
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
        LegacyRenderContext render = beginLegacyRender(
                graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            int centerX = width / 2;
            int contentLeft = panelX + 20;
            int contentRight = panelX + panelWidth - 20;
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            title, centerX, contentLeft, contentRight),
                    centerX, panelY + 18,
                    LegacyLumiTheme.DANGER);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            Component.translatable(
                                    "luma.zones.delete_help", zone.name()),
                            centerX, contentLeft, contentRight),
                    centerX, panelY + 46, LegacyLumiTheme.MUTED);
            LegacyLumiTheme.outlined(
                    graphics, panelX + 20, panelY + 80, panelWidth - 40, 20,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(
                        font, clippedCenteredHeader(
                                errorText(error), centerX,
                                contentLeft, contentRight),
                        centerX, panelY + 118,
                        LegacyLumiTheme.DANGER);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
