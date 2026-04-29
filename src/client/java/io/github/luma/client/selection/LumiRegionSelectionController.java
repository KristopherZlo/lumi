package io.github.luma.client.selection;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public final class LumiRegionSelectionController {

    private static final LumiRegionSelectionController INSTANCE = new LumiRegionSelectionController();
    private static final int MAX_SCOPES = 32;

    private final ProjectService projectService = new ProjectService();
    private final LoadedChunkBlockRaycaster raycaster = new LoadedChunkBlockRaycaster();
    private final Map<SelectionScope, LumiRegionSelectionState> states = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SelectionScope, LumiRegionSelectionState> eldest) {
            return this.size() > MAX_SCOPES;
        }
    };

    private LumiRegionSelectionController() {
    }

    public static LumiRegionSelectionController getInstance() {
        return INSTANCE;
    }

    public boolean selectPrimary(Minecraft client, InteractionHand hand, BlockPos pos) {
        return this.handleClick(client, hand, pos, ClickKind.PRIMARY);
    }

    public boolean selectSecondaryOrToggle(Minecraft client, InteractionHand hand, BlockPos pos) {
        return this.handleClick(client, hand, pos, ClickKind.SECONDARY);
    }

    public boolean handleMouseButton(Minecraft client, int button, int action, int modifiers) {
        if (action != GLFW.GLFW_PRESS || !this.canHandleWorldInput(client)) {
            return false;
        }

        Optional<InteractionHand> hand = this.selectionToolHand(client.player);
        if (hand.isEmpty()) {
            return false;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.altDown(client, modifiers)) {
            return this.clearSelection(client);
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;
        }

        Optional<BlockPos> target = this.raycaster.findTargetBlock(client);
        if (target.isEmpty()) {
            this.notify(client.player, "luma.selection.no_target");
            return true;
        }

        return this.handleClick(
                client,
                hand.get(),
                target.get(),
                button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? ClickKind.PRIMARY : ClickKind.SECONDARY
        );
    }

    public boolean handleScroll(Minecraft client, double horizontalAmount, double verticalAmount) {
        if ((horizontalAmount == 0.0D && verticalAmount == 0.0D) || !this.canHandleWorldInput(client)) {
            return false;
        }
        if (!this.altDown(client, 0) || this.selectionToolHand(client.player).isEmpty()) {
            return false;
        }

        Optional<LumiRegionSelectionState> state = this.currentState(client);
        if (state.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }

        state.get().toggleMode();
        this.notify(client.player, state.get().mode() == LumiRegionSelectionMode.CORNERS
                ? "luma.selection.mode_corners"
                : "luma.selection.mode_extend");
        return true;
    }

    public Optional<Bounds3i> selectedBounds(String projectName, String dimensionId) {
        if (projectName == null || projectName.isBlank() || dimensionId == null || dimensionId.isBlank()) {
            return Optional.empty();
        }
        synchronized (this.states) {
            LumiRegionSelectionState state = this.states.get(new SelectionScope(projectName, dimensionId));
            return state == null ? Optional.empty() : state.bounds();
        }
    }

    private boolean handleClick(Minecraft client, InteractionHand hand, BlockPos pos, ClickKind clickKind) {
        if (client == null || client.player == null || client.level == null || pos == null) {
            return false;
        }
        if (!this.usesSelectionTool(client.player, hand)) {
            return false;
        }

        Optional<SelectionScope> scope = this.currentScope(client);
        if (scope.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }

        LumiRegionSelectionState state = this.stateFor(scope.get());

        BlockPoint point = new BlockPoint(pos.getX(), pos.getY(), pos.getZ());
        if (clickKind == ClickKind.PRIMARY) {
            state.selectPrimary(point);
            this.notify(client.player, "luma.selection.corner_a");
        } else if (state.mode() == LumiRegionSelectionMode.EXTEND) {
            state.selectSecondary(point);
            this.notify(client.player, "luma.selection.reset");
        } else {
            state.selectSecondary(point);
            this.notify(client.player, "luma.selection.corner_b");
        }
        return true;
    }

    private boolean clearSelection(Minecraft client) {
        Optional<LumiRegionSelectionState> state = this.currentState(client);
        if (state.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }
        state.get().clear();
        this.notify(client.player, "luma.selection.cleared");
        return true;
    }

    private Optional<LumiRegionSelectionState> currentState(Minecraft client) {
        Optional<SelectionScope> scope = this.currentScope(client);
        return scope.map(this::stateFor);
    }

    private LumiRegionSelectionState stateFor(SelectionScope scope) {
        synchronized (this.states) {
            return this.states.computeIfAbsent(scope, ignored -> new LumiRegionSelectionState());
        }
    }

    private Optional<SelectionScope> currentScope(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.level == null) {
            return Optional.empty();
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(client);
            ServerLevel level = server.getLevel(client.level.dimension());
            if (level == null) {
                return Optional.empty();
            }
            Optional<BuildProject> project = this.projectService.findWorldProject(level);
            return project.map(value -> new SelectionScope(value.name(), value.dimensionId()));
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Lumi region selection could not resolve current project", exception);
            return Optional.empty();
        }
    }

    private boolean usesSelectionTool(Player player, InteractionHand hand) {
        return hand != null && player.getItemInHand(hand).is(Items.WOODEN_SWORD);
    }

    private Optional<InteractionHand> selectionToolHand(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        if (this.usesSelectionTool(player, InteractionHand.MAIN_HAND)) {
            return Optional.of(InteractionHand.MAIN_HAND);
        }
        if (this.usesSelectionTool(player, InteractionHand.OFF_HAND)) {
            return Optional.of(InteractionHand.OFF_HAND);
        }
        return Optional.empty();
    }

    private boolean canHandleWorldInput(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.screen == null
                && client.getOverlay() == null;
    }

    private boolean altDown(Minecraft client, int modifiers) {
        if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) {
            return true;
        }
        if (client == null || client.getWindow() == null) {
            return false;
        }
        long window = client.getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private void notify(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private enum ClickKind {
        PRIMARY,
        SECONDARY
    }

    private record SelectionScope(String projectName, String dimensionId) {
    }
}
