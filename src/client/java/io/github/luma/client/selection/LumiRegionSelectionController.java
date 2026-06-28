package io.github.luma.client.selection;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class LumiRegionSelectionController {

    private static final LumiRegionSelectionController INSTANCE = new LumiRegionSelectionController();
    private static final int MAX_SCOPES = 32;

    private final ProjectService projectService = new ProjectService();
    private final WorkZoneService workZoneService = new WorkZoneService();
    private final LoadedChunkBlockRaycaster raycaster = new LoadedChunkBlockRaycaster();
    private final Map<SelectionScope, LumiRegionSelectionState> states = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SelectionScope, LumiRegionSelectionState> eldest) {
            return this.size() > MAX_SCOPES;
        }
    };
    private KeyMapping actionButton;
    private KeyBindingState keyBindingState;

    private LumiRegionSelectionController() {
    }

    public static LumiRegionSelectionController getInstance() {
        return INSTANCE;
    }

    public void configureActionButton(KeyMapping actionButton, KeyBindingState keyBindingState) {
        this.actionButton = actionButton;
        this.keyBindingState = keyBindingState;
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

        if ((button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) && controlDown(client)) {
            return this.handleZoneEdit(client, button == GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.actionButtonDown(client)) {
            return this.toggleMode(client);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.actionButtonDown(client)) {
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
        if (!this.actionButtonDown(client) || this.selectionToolHand(client.player).isEmpty()) {
            return false;
        }

        Optional<LumiRegionSelectionState> state = this.currentState(client);
        if (state.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }

        Optional<Bounds3i> bounds = state.get().bounds();
        if (bounds.isEmpty()) {
            this.notify(client.player, "luma.selection.no_selection");
            return true;
        }

        Optional<BlockPos> target = this.raycaster.findTargetBlock(client);
        Vec3 eye = client.player.getEyePosition(1.0F);
        Vec3 view = client.player.getViewVector(1.0F).normalize();
        LumiRegionSelectionState.Side side = SelectionResizeSideResolver.resolve(
                bounds.get(),
                target.map(BlockPoint::from).orElse(null),
                view
        );
        double scroll = verticalAmount == 0.0D ? horizontalAmount : verticalAmount;
        state.get().resize(side, SelectionResizeSideResolver.amountForScroll(bounds.get(), eye, view, scroll));
        this.notify(client.player, "luma.selection.resized");
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

    public Optional<LumiRegionSelectionMode> currentMode(Minecraft client) {
        return this.currentState(client).map(LumiRegionSelectionState::mode);
    }

    public boolean handleUndoRedo(Minecraft client, boolean undo) {
        if (!this.canHandleWorldInput(client) || this.selectionToolHand(client.player).isEmpty()) {
            return false;
        }
        Optional<LumiRegionSelectionState> state = this.currentState(client);
        if (state.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }
        boolean changed = undo ? state.get().undo() : state.get().redo();
        this.notify(client.player, changed
                ? undo ? "luma.selection.undo" : "luma.selection.redo"
                : undo ? "luma.selection.no_undo" : "luma.selection.no_redo");
        return true;
    }

    public boolean shouldRenderSelection(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.screen == null
                && client.getOverlay() == null
                && this.selectionToolHand(client.player).isPresent();
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

    private boolean toggleMode(Minecraft client) {
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

    private boolean handleZoneEdit(Minecraft client, boolean add) {
        Optional<SelectionContext> context = this.currentContext(client);
        if (context.isEmpty()) {
            this.notify(client.player, "luma.selection.no_project");
            return true;
        }
        Optional<BlockPos> target = this.raycaster.findTargetBlock(client);
        List<WorkZoneCell> cells = this.zoneEditCells(context.get(), target);
        if (cells.isEmpty()) {
            this.notify(client.player, "luma.selection.no_target");
            return true;
        }
        try {
            Optional<WorkZone> zone = add
                    ? this.workZoneService.addCells(context.get().layout(), context.get().actor(), cells, Instant.now())
                    : this.workZoneService.removeCells(context.get().layout(), context.get().actor(), cells, Instant.now());
            this.notify(client.player, zone.isEmpty()
                    ? "luma.selection.zone_no_active"
                    : add ? "luma.selection.zone_added" : "luma.selection.zone_removed");
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Lumi zone edit failed", exception);
            this.notify(client.player, "luma.selection.zone_failed");
        }
        return true;
    }

    private List<WorkZoneCell> zoneEditCells(SelectionContext context, Optional<BlockPos> target) {
        LumiRegionSelectionState state = this.stateFor(context.scope());
        Optional<Bounds3i> bounds = state.bounds();
        if (bounds.isPresent()) {
            return cellsIn(bounds.get());
        }
        return target.map(pos -> List.of(WorkZoneCell.from(BlockPoint.from(pos)))).orElseGet(List::of);
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
        return this.currentContext(client).map(SelectionContext::scope);
    }

    private Optional<SelectionContext> currentContext(Minecraft client) {
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
            if (project.isEmpty()) {
                return Optional.empty();
            }
            BuildProject value = project.get();
            return Optional.of(new SelectionContext(
                    new SelectionScope(value.name(), value.dimensionId()),
                    this.projectService.resolveLayout(server, value.name()),
                    this.actor(client)
            ));
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

    private boolean actionButtonDown(Minecraft client) {
        if (this.actionButton == null || this.keyBindingState == null) {
            return false;
        }
        return this.keyBindingState.isDown(client, this.actionButton);
    }

    static boolean controlDown(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        var window = client.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private String actor(Minecraft client) {
        return client.getUser() == null ? "player" : client.getUser().getName();
    }

    private static List<WorkZoneCell> cellsIn(Bounds3i bounds) {
        WorkZoneCell min = WorkZoneCell.from(bounds.min());
        WorkZoneCell max = WorkZoneCell.from(bounds.max());
        List<WorkZoneCell> cells = new ArrayList<>();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    cells.add(new WorkZoneCell(x, y, z));
                }
            }
        }
        return cells;
    }

    private void notify(Player player, String key) {
        player.displayClientMessage(ActionBarMessagePresenter.selection(key), true);
    }

    private enum ClickKind {
        PRIMARY,
        SECONDARY
    }

    private record SelectionScope(String projectName, String dimensionId) {
    }

    private record SelectionContext(SelectionScope scope, ProjectLayout layout, String actor) {
    }
}
