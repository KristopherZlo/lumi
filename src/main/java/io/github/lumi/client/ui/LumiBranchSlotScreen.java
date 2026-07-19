package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.state.ClientBranchSlotStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Captures one arbitrary keyboard key for a branch's Alt chord. */
public final class LumiBranchSlotScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 210;
    private final Screen parent;
    private final HistorySnapshotPayload snapshot;
    private final HistorySnapshotPayload.Branch branch;
    private final ClientBranchSlotStore slots;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public LumiBranchSlotScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.Branch branch,
            ClientBranchSlotStore slots) {
        super(Component.translatable(
                "luma.ideas.bind_title", shortName(branch.name())));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.branch = Objects.requireNonNull(branch, "branch");
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        addLegacyButton(
                panelX + 20, panelY + actionOffset(panelHeight), panelWidth - 40,
                Component.translatable("luma.action.clear_bind"),
                this::clear, LumiLegacyButton.Kind.DANGER);
    }

    private void clear() {
        slots.clear(snapshot, branch.name());
        feedback("luma.status.variant_switch_key_updated");
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        try {
            slots.assignKey(snapshot, branch.name(), event.key());
            feedback("luma.status.variant_switch_key_updated");
            onClose();
        } catch (IllegalArgumentException invalid) {
            feedback(invalid.getMessage());
        }
        return true;
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(key), true);
        }
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render =
                beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            title, width / 2,
                            panelX + 16, panelX + panelWidth - 16),
                    width / 2, panelY + 18,
                    LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(
                    font, Component.translatable(
                            "luma.ideas.bind_help",
                            LumiHotkeys.bindingLabel(
                                    minecraft.options.keyMappings,
                                    "key.lumi.action_modifier")),
                    width / 2, panelY + 44, LegacyLumiTheme.MUTED);
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    static LegacyModalLayout fitPanel(int screenWidth, int screenHeight) {
        int width = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 32));
        int height = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LegacyModalLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2), width, height);
    }

    static int actionOffset(int panelHeight) {
        return panelHeight - 42;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
