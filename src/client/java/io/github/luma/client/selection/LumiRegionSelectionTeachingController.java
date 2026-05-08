package io.github.luma.client.selection;

import io.github.luma.LumaMod;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private Component cachedActionKey = Component.empty();

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

        List<Component> lines = List.of(
                Component.translatable("luma.selection.hud_primary"),
                Component.translatable("luma.selection.hud_secondary"),
                Component.translatable("luma.selection.hud_clear", this.cachedActionKey),
                Component.translatable("luma.selection.hud_mode", this.cachedActionKey)
        );
        int lineHeight = 10;
        int width = lines.stream()
                .mapToInt(client.font::width)
                .max()
                .orElse(1) + 12;
        int height = (lines.size() * lineHeight) + 8;
        int x = 8;
        int y = Math.max(8, graphics.guiHeight() - height - 8);

        graphics.fill(x, y, x + width, y + height, 0xB80B1016);
        graphics.renderOutline(x, y, width, height, 0xFF35C6FF);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(client.font, lines.get(index), x + 6, y + 5 + (index * lineHeight), 0xFFF3F7FA, false);
        }
    }

    private Component actionKey() {
        KeyMapping key = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        if (key == null || key.isUnbound()) {
            return Component.translatable("luma.onboarding.key_unbound");
        }
        return key.getTranslatedKeyMessage();
    }
}
