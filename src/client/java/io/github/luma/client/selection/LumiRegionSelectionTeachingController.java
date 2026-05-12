package io.github.luma.client.selection;

import io.github.luma.LumaMod;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.overlay.CompareOverlayHotkeyHud;
import io.github.luma.ui.overlay.RoundedHudRenderer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Shows the low-noise first-use teaching hint for Lumi's wooden-sword region selector.
 */
public final class LumiRegionSelectionTeachingController {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "selection_tool_hint"
    );

    private final ProjectService projectService;
    private final ClientContextualHelpService helpService;
    private final SelectionToolTeachingState teachingState;
    private boolean hudVisible;
    private KeyMapping cachedActionKey;

    public LumiRegionSelectionTeachingController() {
        this(new ProjectService(), new ClientContextualHelpService(), new SelectionToolTeachingState());
    }

    LumiRegionSelectionTeachingController(
            ProjectService projectService,
            ClientContextualHelpService helpService,
            SelectionToolTeachingState teachingState
    ) {
        this.projectService = projectService;
        this.helpService = helpService;
        this.teachingState = teachingState;
    }

    public void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE,
                HUD_ELEMENT_ID,
                this::render
        );
    }

    public void tick(Minecraft client) {
        boolean inputActive = this.canTeach(client);
        boolean toolHeld = inputActive && this.selectionToolHeld(client.player);
        this.hudVisible = toolHeld;
        this.cachedActionKey = this.actionKey();
        if (!toolHeld) {
            return;
        }
        if (this.teachingState.active() && this.teachingState.tickDisplay()) {
            this.helpService.dismissHint(ClientContextualHelpHint.SELECTION_TOOL);
        }

        boolean hintAllowed = this.helpService.shouldShowHint(ClientContextualHelpHint.SELECTION_TOOL);
        this.teachingState.observeHintAllowed(hintAllowed);
        if (!this.teachingState.shouldStart(inputActive, true, hintAllowed)) {
            return;
        }

        this.teachingState.start();
    }

    private boolean canTeach(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.screen != null
                || client.getOverlay() != null
                || !client.hasSingleplayerServer()) {
            return false;
        }
        try {
            ServerLevel level = ClientProjectAccess.requireSingleplayerServer(client).getLevel(client.level.dimension());
            if (level == null) {
                return false;
            }
            return this.projectService.findWorldProject(level).isPresent();
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean selectionToolHeld(Player player) {
        return player != null
                && (player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.WOODEN_SWORD)
                || player.getItemInHand(InteractionHand.OFF_HAND).is(Items.WOODEN_SWORD));
    }

    private void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!this.hudVisible || client == null || client.options.hideGui) {
            return;
        }

        Font font = client.font;
        List<Row> rows = List.of(
                new Row(List.of("LMB"), "First corner"),
                new Row(List.of("RMB"), "Second corner"),
                new Row(List.of("ACTION", "RMB"), "Clear"),
                new Row(List.of("ACTION", "Scroll"), "Mode")
        );
        int lineHeight = 16;
        int width = rows.stream()
                .mapToInt(row -> this.rowWidth(font, row))
                .max()
                .orElse(1) + 16;
        int height = (rows.size() * lineHeight) + 8;
        int x = 8;
        int y = Math.max(8, graphics.guiHeight() - height - 8 - CompareOverlayHotkeyHud.reservedBottomHeight());

        RoundedHudRenderer.card(graphics, x, y, width, height);
        for (int index = 0; index < rows.size(); index++) {
            this.drawRow(graphics, font, rows.get(index), x + 8, y + 4 + (index * lineHeight));
        }
    }

    private int rowWidth(Font font, Row row) {
        return this.keyGroupWidth(row.keys()) + 6 + font.width(row.text());
    }

    private int keyGroupWidth(List<String> keys) {
        int width = 0;
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                width += 3;
            }
            String key = keys.get(index);
            width += "ACTION".equals(key)
                    ? RoundedHudRenderer.keyWidth(this.cachedActionKey, "Alt", true)
                    : RoundedHudRenderer.textChipWidth(key, true);
        }
        return width;
    }

    private void drawRow(GuiGraphics graphics, Font font, Row row, int x, int y) {
        int cursor = x;
        for (int index = 0; index < row.keys().size(); index++) {
            if (index > 0) {
                cursor += 3;
            }
            String key = row.keys().get(index);
            cursor += "ACTION".equals(key)
                    ? RoundedHudRenderer.key(graphics, this.cachedActionKey, cursor, y, "Alt", true)
                    : RoundedHudRenderer.textChip(graphics, key, cursor, y, true);
        }
        graphics.drawString(font, Component.literal(": " + row.text()), cursor + 4, y + 3, RoundedHudRenderer.MUTED, false);
    }

    private KeyMapping actionKey() {
        return LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
    }

    private record Row(List<String> keys, String text) {
    }
}
