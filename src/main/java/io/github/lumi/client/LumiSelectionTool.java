package io.github.lumi.client;

import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.domain.model.BlockPosition;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

/** Wooden-sword client selection; no world mutation or packet is produced. */
public final class LumiSelectionTool {
    private final ClientSelection selection;
    private final Consumer<String> feedback;

    public LumiSelectionTool(ClientSelection selection, Consumer<String> feedback) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    public void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, position, direction) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !player.getItemInHand(hand).is(Items.WOODEN_SWORD)) {
                return InteractionResult.PASS;
            }
            selection.setFirst(new BlockPosition(
                    position.getX(), position.getY(), position.getZ()));
            player.swing(hand);
            feedback.accept("luma.selection.corner_a");
            return InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !player.getItemInHand(hand).is(Items.WOODEN_SWORD)) {
                return InteractionResult.PASS;
            }
            var position = hit.getBlockPos();
            selection.setSecond(new BlockPosition(
                    position.getX(), position.getY(), position.getZ()));
            player.swing(hand);
            feedback.accept("luma.selection.corner_b");
            return InteractionResult.FAIL;
        });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> selection.reset());
    }
}
