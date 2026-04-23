package io.github.luma.gbreak.server;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.state.BugStateController;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

final class PlayerInteractionBugService {

    private final BugStateController bugState = BugStateController.getInstance();
    private final GhostDisplayService ghostDisplayService;

    PlayerInteractionBugService(GhostDisplayService ghostDisplayService) {
        this.ghostDisplayService = ghostDisplayService;
    }

    ActionResult handleUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        GameBreakingBug activeBug = this.bugState.activeBug();
        if (activeBug == GameBreakingBug.GHOST_PLAYER) {
            return ActionResult.SUCCESS_SERVER;
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }

        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.PASS;
        }

        PlacementPlan plan = this.resolvePlacement(serverPlayer, serverWorld, hand, hitResult, blockItem, stack);
        if (plan == null) {
            return ActionResult.PASS;
        }

        if (activeBug == GameBreakingBug.THE_GHOST) {
            this.consumePlacementItem(serverPlayer, stack);
            this.ghostDisplayService.spawn(serverWorld, plan.pos(), plan.state());
            return ActionResult.SUCCESS_SERVER;
        }

        return ActionResult.PASS;
    }

    boolean beforeBlockBreak(ServerWorld world, PlayerEntity player, BlockPos pos, BlockState state) {
        GameBreakingBug activeBug = this.bugState.activeBug();
        if (activeBug == GameBreakingBug.GHOST_PLAYER) {
            return false;
        }
        if (activeBug != GameBreakingBug.NO_BLOCK_UPDATES) {
            return true;
        }

        return true;
    }

    private PlacementPlan resolvePlacement(
            ServerPlayerEntity player,
            ServerWorld world,
            Hand hand,
            BlockHitResult hitResult,
            BlockItem blockItem,
            ItemStack stack
    ) {
        ItemPlacementContext baseContext = new ItemPlacementContext(player, hand, stack, hitResult);
        ItemPlacementContext placementContext = blockItem.getPlacementContext(baseContext);
        if (placementContext == null || !placementContext.canPlace()) {
            return null;
        }

        BlockPos pos = placementContext.getBlockPos();
        BlockState state = blockItem.getBlock().getPlacementState(placementContext);
        if (state == null) {
            return null;
        }
        if (!this.canPlace(world, player, pos, state)) {
            return null;
        }
        return new PlacementPlan(pos, state);
    }

    private boolean canPlace(World world, PlayerEntity player, BlockPos pos, BlockState state) {
        return state.canPlaceAt(world, pos) && world.canPlace(state, pos, ShapeContext.of(player));
    }

    private void consumePlacementItem(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getAbilities().creativeMode) {
            stack.decrement(1);
        }
    }

    private record PlacementPlan(BlockPos pos, BlockState state) {
    }
}
