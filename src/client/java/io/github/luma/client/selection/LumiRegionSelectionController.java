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

public final class LumiRegionSelectionController {

    private static final LumiRegionSelectionController INSTANCE = new LumiRegionSelectionController();
    private static final int MAX_SCOPES = 32;

    private final ProjectService projectService = new ProjectService();
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

        LumiRegionSelectionState state;
        synchronized (this.states) {
            state = this.states.computeIfAbsent(scope.get(), ignored -> new LumiRegionSelectionState());
        }
        if (clickKind == ClickKind.SECONDARY && client.player.isShiftKeyDown()) {
            state.toggleMode();
            this.notify(client.player, state.mode() == LumiRegionSelectionMode.CORNERS
                    ? "luma.selection.mode_corners"
                    : "luma.selection.mode_extend");
            return true;
        }

        BlockPoint point = new BlockPoint(pos.getX(), pos.getY(), pos.getZ());
        if (clickKind == ClickKind.PRIMARY) {
            state.selectPrimary(point);
            this.notify(client.player, "luma.selection.corner_a");
        } else {
            state.selectSecondary(point);
            this.notify(client.player, "luma.selection.corner_b");
        }
        return true;
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
