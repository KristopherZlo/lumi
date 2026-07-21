package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation for a durable soft-delete marker. */
public final class LumiDeleteVersionScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 180;
    private final Screen parent;
    private final HistorySnapshotPayload.Version version;
    private final Function<CommitId, UUID> delete;
    private LumiButton submit;
    private UUID requestId;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiDeleteVersionScreen(
            Screen parent,
            HistorySnapshotPayload.Version version,
            Function<CommitId, UUID> delete) {
        super(Component.translatable("luma.save_details.delete_title"));
        this.parent = parent;
        this.version = Objects.requireNonNull(version, "version");
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    @Override
    protected void init() {
        beginScreenInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int buttonWidth = (panelWidth - 48) / 2;
        submit = addButton(panelX + 20, panelY + 138, buttonWidth,
                Component.translatable("luma.action.delete_save"),
                this::delete, LumiButton.Kind.DANGER);
        addButton(panelX + 28 + buttonWidth, panelY + 138, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
        submit.active = requestId == null;
    }

    private void delete() {
        if (requestId != null) return;
        try {
            requestId = delete.apply(version.id());
            error = "";
            submit.active = false;
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi save could not be deleted" : failed.getMessage();
        }
    }

    public boolean accept(OperationEventPayload event) {
        if (requestId == null || !requestId.equals(event.requestId())) return false;
        if (event.state() == OperationEventPayload.State.SUCCEEDED) {
            requestId = null;
            minecraft.setScreen(parent);
            return true;
        }
        if (event.state() == OperationEventPayload.State.FAILED
                || event.state() == OperationEventPayload.State.CANCELLED
                || event.state() == OperationEventPayload.State.DEGRADED) {
            requestId = null;
            error = event.message().isBlank()
                    ? "Lumi save could not be deleted" : event.message();
            submit.active = true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            int centerX = width / 2;
            int contentLeft = panelX + 20;
            int contentRight = panelX + panelWidth - 20;
            renderWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    title, centerX, contentLeft, contentRight),
                    centerX, panelY + 18, LumiTheme.TEXT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.literal(version.message()),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 42, LumiTheme.TEXT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.save_details.delete_help"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 68, LumiTheme.MUTED);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.save_details.delete_warning"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 90, LumiTheme.ACCENT);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(font, clippedCenteredHeader(
                        errorText(error), centerX, contentLeft, contentRight),
                        centerX, panelY + 116, LumiTheme.DANGER);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
