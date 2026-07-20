package io.github.lumi.client;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.client.state.SelectionResizeSideResolver;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/** Raw wooden-sword world input for scoped Lumi selections. */
public final class LumiSelectionTool {
    private static LumiSelectionTool active;
    private final ClientSelection selection;
    private final ClientHistoryStore history;
    private final Consumer<String> feedback;
    private final BiConsumer<Boolean, BlockBox> editZone;

    public LumiSelectionTool(
            ClientSelection selection,
            ClientHistoryStore history,
            Consumer<String> feedback,
            BiConsumer<Boolean, BlockBox> editZone) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.history = Objects.requireNonNull(history, "history");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.editZone = Objects.requireNonNull(editZone, "editZone");
    }

    public void register() {
        active = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::activateScope);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            selection.reset();
            active = this;
        });
    }

    public static boolean handleMouseButton(
            Minecraft client, int button, int action, int modifiers) {
        return active != null
                && active.onMouseButton(client, button, action, modifiers);
    }

    public static boolean handleScroll(
            Minecraft client, double horizontal, double vertical) {
        return active != null && active.onScroll(client, horizontal, vertical);
    }

    public static boolean held(Minecraft client) {
        return toolHand(client).isPresent();
    }

    private boolean onMouseButton(
            Minecraft client, int button, int action, int modifiers) {
        if (action != GLFW.GLFW_PRESS || !canHandle(client)
                || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT
                && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return false;
        }
        Optional<InteractionHand> hand = toolHand(client);
        if (hand.isEmpty()) return false;
        activateScope(client);
        if (controlDown(client)) {
            editZone(client, button == GLFW.GLFW_MOUSE_BUTTON_LEFT);
            client.player.swing(hand.orElseThrow());
            return true;
        }
        if (actionModifierDown(client)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                selection.toggleMode();
                feedback.accept(selection.mode()
                        == io.github.lumi.client.state.SelectionMode.CORNERS
                        ? "luma.selection.mode_corners"
                        : "luma.selection.mode_extend");
            } else {
                selection.clear();
                feedback.accept("luma.selection.cleared");
            }
            return true;
        }
        Optional<BlockPosition> target = target(client);
        if (target.isEmpty()) {
            feedback.accept("luma.selection.no_target");
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            selection.setFirst(target.orElseThrow());
            feedback.accept("luma.selection.corner_a");
        } else {
            selection.setSecond(target.orElseThrow());
            feedback.accept("luma.selection.corner_b");
        }
        client.player.swing(hand.orElseThrow());
        return true;
    }

    private void editZone(Minecraft client, boolean add) {
        Optional<BlockBox> area = selection.bounds().or(() ->
                target(client).map(position -> new BlockBox(
                        position.x(), position.y(), position.z(),
                        position.x(), position.y(), position.z())));
        if (area.isEmpty()) {
            feedback.accept("luma.selection.no_target");
            return;
        }
        try {
            editZone.accept(add, area.orElseThrow());
            feedback.accept(add
                    ? "luma.selection.zone_added"
                    : "luma.selection.zone_removed");
        } catch (RuntimeException failed) {
            feedback.accept("luma.selection.zone_failed");
        }
    }

    private boolean onScroll(
            Minecraft client, double horizontal, double vertical) {
        if ((horizontal == 0 && vertical == 0) || !canHandle(client)
                || !held(client) || !actionModifierDown(client)) {
            return false;
        }
        activateScope(client);
        var bounds = selection.bounds();
        if (bounds.isEmpty()) {
            feedback.accept("luma.selection.no_selection");
            return true;
        }
        var eye = client.player.getEyePosition(1.0F);
        var view = client.player.getViewVector(1.0F).normalize();
        double scroll = vertical == 0 ? horizontal : vertical;
        selection.resize(
                SelectionResizeSideResolver.resolve(
                        bounds.orElseThrow(), target(client).orElse(null), view),
                SelectionResizeSideResolver.amountForScroll(
                        bounds.orElseThrow(), eye, view, scroll));
        feedback.accept("luma.selection.resized");
        return true;
    }

    private void activateScope(Minecraft client) {
        if (client == null || client.level == null) return;
        history.state().snapshot().ifPresent(snapshot ->
                selection.activate(snapshot.workspaceId(), snapshot.dimensionId()));
    }

    private static Optional<BlockPosition> target(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult block)
                || block.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        var position = block.getBlockPos();
        if (!client.level.hasChunkAt(position)
                || client.level.getBlockState(position).isAir()) {
            return Optional.empty();
        }
        return Optional.of(new BlockPosition(
                position.getX(), position.getY(), position.getZ()));
    }

    private static Optional<InteractionHand> toolHand(Minecraft client) {
        if (client == null || client.player == null) return Optional.empty();
        if (client.player.getMainHandItem().is(Items.WOODEN_SWORD)) {
            return Optional.of(InteractionHand.MAIN_HAND);
        }
        return client.player.getOffhandItem().is(Items.WOODEN_SWORD)
                ? Optional.of(InteractionHand.OFF_HAND) : Optional.empty();
    }

    private static boolean canHandle(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && client.screen == null && client.getOverlay() == null;
    }

    private static boolean actionModifierDown(Minecraft client) {
        return LumiHotkeys.actionModifierDown(client.options.keyMappings);
    }

    private static boolean controlDown(Minecraft client) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                client.getWindow(),
                com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                        client.getWindow(),
                        com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    }
}
