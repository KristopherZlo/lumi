package io.github.luma.minecraft.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives deterministic server-side player actions through Minecraft's normal game-mode APIs.
 */
final class SingleplayerPlayerActionDriver {

    private final ServerLevel level;
    private final ServerPlayer player;

    SingleplayerPlayerActionDriver(ServerLevel level, ServerPlayer player) {
        this.level = level;
        this.player = player;
        this.player.setGameMode(GameType.CREATIVE);
    }

    boolean placeAgainst(BlockPos clickedPos, Direction face, Block block, BlockPos expectedPos) {
        InteractionResult result = this.performUseItemOn(clickedPos, face, new ItemStack(block, 64));
        return result.consumesAction() && this.level.getBlockState(expectedPos).is(block);
    }

    boolean useItemOn(BlockPos clickedPos, Direction face, ItemStack stack) {
        return this.performUseItemOn(clickedPos, face, stack).consumesAction();
    }

    private InteractionResult performUseItemOn(BlockPos clickedPos, Direction face, ItemStack stack) {
        this.moveNear(clickedPos);
        this.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hitResult = new BlockHitResult(this.hitLocation(clickedPos, face), face, clickedPos, false);
        return this.player.gameMode.useItemOn(this.player, this.level, stack, InteractionHand.MAIN_HAND, hitResult);
    }

    boolean destroyBlock(BlockPos pos) {
        this.moveNear(pos);
        return this.player.gameMode.destroyBlock(pos);
    }

    private void moveNear(BlockPos pos) {
        this.player.teleportTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 2.5D);
        this.player.snapTo(this.player.getX(), this.player.getY(), this.player.getZ(), 180.0F, 30.0F);
    }

    private Vec3 hitLocation(BlockPos pos, Direction face) {
        double x = pos.getX() + 0.5D + 0.5D * face.getStepX();
        double y = pos.getY() + 0.5D + 0.5D * face.getStepY();
        double z = pos.getZ() + 0.5D + 0.5D * face.getStepZ();
        return new Vec3(x, y, z);
    }
}
