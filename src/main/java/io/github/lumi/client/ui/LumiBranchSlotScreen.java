package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.state.ClientBranchSlotStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit Action-key slot assignment for one visible branch. */
public final class LumiBranchSlotScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 210;
    private final Screen parent;
    private final HistorySnapshotPayload snapshot;
    private final HistorySnapshotPayload.Branch branch;
    private final ClientBranchSlotStore slots;
    private int panelX;
    private int panelY;

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
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int gap = 6;
        int buttonWidth = (panelWidth - 40 - gap * 4) / 5;
        for (int slot = 0; slot < ClientBranchSlotStore.SLOT_COUNT; slot++) {
            int selectedSlot = slot;
            int x = panelX + 20 + (slot % 5) * (buttonWidth + gap);
            int y = panelY + 78 + (slot / 5) * 30;
            addLegacyButton(
                    x, y, buttonWidth, Component.literal(binding(slot)),
                    () -> assign(selectedSlot),
                    slots.slot(snapshot, branch.name()).isPresent()
                            && slots.slot(snapshot, branch.name()).getAsInt() == slot
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
        }
        int actionWidth = (panelWidth - 48) / 2;
        addLegacyButton(
                panelX + 20, panelY + 168, actionWidth,
                Component.translatable("luma.action.clear_bind"),
                this::clear, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(
                panelX + 28 + actionWidth, panelY + 168, actionWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void assign(int slot) {
        slots.assign(snapshot, branch.name(), slot);
        feedback("luma.status.variant_switch_key_updated");
        onClose();
    }

    private void clear() {
        slots.clear(snapshot, branch.name());
        feedback("luma.status.variant_switch_key_updated");
        onClose();
    }

    private String binding(int slot) {
        String action = LumiHotkeys.bindingLabel(
                minecraft.options.keyMappings, "key.lumi.action_modifier");
        String number = LumiHotkeys.bindingLabel(
                minecraft.options.keyMappings, slotKey(slot));
        return action + " + " + number;
    }

    private static String slotKey(int slot) {
        return "key.lumi.branch_slot." + (slot == 9 ? "0" : slot + 1);
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
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawCenteredString(
                    font, title, width / 2, panelY + 18,
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

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
