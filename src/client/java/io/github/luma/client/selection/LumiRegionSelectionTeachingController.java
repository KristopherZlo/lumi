package io.github.luma.client.selection;

import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Shows the low-noise first-use teaching hint for Lumi's wooden-sword region selector.
 */
public final class LumiRegionSelectionTeachingController {

    private final ProjectService projectService;
    private final ClientContextualHelpService helpService;
    private final SelectionToolTeachingState teachingState;

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

    public void tick(Minecraft client) {
        if (this.teachingState.active()) {
            if (!this.canTeach(client)) {
                return;
            }
            this.showHint(client);
            if (this.teachingState.tickDisplay()) {
                this.helpService.dismissHint(ClientContextualHelpHint.SELECTION_TOOL);
            }
            return;
        }

        boolean inputActive = this.canTeach(client);
        boolean toolHeld = inputActive && this.selectionToolHeld(client.player);
        if (!toolHeld) {
            return;
        }

        boolean hintAllowed = this.helpService.shouldShowHint(ClientContextualHelpHint.SELECTION_TOOL);
        this.teachingState.observeHintAllowed(hintAllowed);
        if (!this.teachingState.shouldStart(inputActive, true, hintAllowed)) {
            return;
        }

        this.teachingState.start();
        this.showHint(client);
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

    private void showHint(Minecraft client) {
        if (client == null || client.gui == null) {
            return;
        }
        client.gui.setOverlayMessage(ActionBarMessagePresenter.selectionToolHint(this.actionKey()), false);
    }

    private Component actionKey() {
        KeyMapping key = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        if (key == null || key.isUnbound()) {
            return Component.translatable("luma.onboarding.key_unbound");
        }
        return key.getTranslatedKeyMessage();
    }
}
