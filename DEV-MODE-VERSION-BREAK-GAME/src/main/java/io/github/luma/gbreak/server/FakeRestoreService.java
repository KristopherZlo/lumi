package io.github.luma.gbreak.server;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class FakeRestoreService {

    private static final int DEFAULT_TOTAL_REPLACEMENTS = 24;
    private static final int REPLACEMENTS_PER_TICK = 4;
    private static final int HORIZONTAL_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 4;
    private static final int MAX_SEARCH_ATTEMPTS = 96;
    private static final Set<Block> UNSAFE_BLOCKS = Set.of(
            Blocks.AIR,
            Blocks.CAVE_AIR,
            Blocks.VOID_AIR,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.BEDROCK,
            Blocks.BARRIER,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.STRUCTURE_BLOCK,
            Blocks.JIGSAW
    );

    private FakeRestoreOperation activeOperation;

    public boolean start(ServerPlayerEntity player, String targetLabel) {
        if (player == null) {
            return false;
        }
        if (this.activeOperation != null && !this.activeOperation.complete()) {
            return false;
        }

        this.activeOperation = new FakeRestoreOperation(
                player.getUuid(),
                player.getEntityWorld().getRegistryKey(),
                player.getBlockPos().toImmutable(),
                this.normalizeTargetLabel(targetLabel),
                DEFAULT_TOTAL_REPLACEMENTS,
                0
        );
        player.sendMessage(Text.translatable("gbreakdev.restore.started", this.activeOperation.targetLabel()), true);
        return true;
    }

    public void tick(MinecraftServer server) {
        if (this.activeOperation == null) {
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(this.activeOperation.playerId());
        ServerWorld world = server.getWorld(this.activeOperation.worldKey());
        if (player == null || world == null) {
            this.activeOperation = null;
            return;
        }

        int replacedThisTick = 0;
        for (int step = 0; step < REPLACEMENTS_PER_TICK && !this.activeOperation.complete(); step++) {
            BlockPos targetPos = this.findTarget(world, this.activeOperation.center());
            if (targetPos == null) {
                break;
            }

            world.setBlockState(targetPos, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
            this.activeOperation = this.activeOperation.advance(1);
            replacedThisTick++;
        }

        if (replacedThisTick == 0) {
            player.sendMessage(Text.translatable("gbreakdev.restore.completed", this.activeOperation.targetLabel()), true);
            this.activeOperation = null;
            return;
        }

        player.sendMessage(Text.translatable(
                "gbreakdev.restore.progress",
                this.activeOperation.targetLabel(),
                this.activeOperation.appliedReplacements(),
                this.activeOperation.totalReplacements(),
                this.activeOperation.progressPercent()
        ), true);

        if (this.activeOperation.complete()) {
            player.sendMessage(Text.translatable("gbreakdev.restore.completed", this.activeOperation.targetLabel()), true);
            this.activeOperation = null;
        }
    }

    private BlockPos findTarget(ServerWorld world, BlockPos center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_SEARCH_ATTEMPTS; attempt++) {
            BlockPos candidatePos = center.add(
                    random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1),
                    random.nextInt(-VERTICAL_RADIUS, VERTICAL_RADIUS + 1),
                    random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1)
            );
            BlockState state = world.getBlockState(candidatePos);
            if (this.isCandidate(world, candidatePos, state)) {
                return candidatePos.toImmutable();
            }
        }
        return null;
    }

    private boolean isCandidate(ServerWorld world, BlockPos pos, BlockState state) {
        return !state.isAir()
                && !state.hasBlockEntity()
                && !state.isOf(Blocks.GLASS)
                && !UNSAFE_BLOCKS.contains(state.getBlock())
                && this.isExposed(world, pos);
    }

    private boolean isExposed(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.offset(direction);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.getCollisionShape(world, neighborPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTargetLabel(String targetLabel) {
        if (targetLabel == null || targetLabel.isBlank()) {
            return "latest";
        }
        return targetLabel;
    }
}
