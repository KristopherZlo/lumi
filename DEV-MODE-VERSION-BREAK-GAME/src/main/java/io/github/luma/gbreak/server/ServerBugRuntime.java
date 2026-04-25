package io.github.luma.gbreak.server;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.server.animal.AnimalMoveManager;
import io.github.luma.gbreak.state.BugStateController;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public final class ServerBugRuntime {

    private final BugStateController bugState = BugStateController.getInstance();
    private final GhostDisplayService ghostDisplayService = new GhostDisplayService();
    private final PlayerInteractionBugService playerInteractionBugService = new PlayerInteractionBugService(this.ghostDisplayService);
    private final CorruptedBlocksService corruptedBlocksService = new CorruptedBlocksService();
    private final FakeRestoreService fakeRestoreService = new FakeRestoreService();
    private final WorldCorruptionService worldCorruptionService = new WorldCorruptionService();
    private final AnimalMoveManager animalMoveManager = new AnimalMoveManager();

    public FakeRestoreService fakeRestoreService() {
        return this.fakeRestoreService;
    }

    public WorldCorruptionService worldCorruptionService() {
        return this.worldCorruptionService;
    }

    public AnimalMoveManager animalMoveManager() {
        return this.animalMoveManager;
    }

    public void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> world.isClient()
                ? net.minecraft.util.ActionResult.PASS
                : this.playerInteractionBugService.handleUseBlock(player, world, hand, hitResult));
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> world instanceof ServerWorld serverWorld
                ? this.beforeBlockBreak(serverWorld, player, pos, state, blockEntity)
                : true);
        ServerTickEvents.START_SERVER_TICK.register(this::startTick);
        ServerTickEvents.END_SERVER_TICK.register(this::endTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.worldCorruptionService.restoreAllImmediately(server);
            this.animalMoveManager.shutdown(server);
        });
    }

    private boolean beforeBlockBreak(
            ServerWorld world,
            PlayerEntity player,
            BlockPos pos,
            BlockState state,
            net.minecraft.block.entity.BlockEntity blockEntity
    ) {
        return this.playerInteractionBugService.beforeBlockBreak(world, player, pos, state);
    }

    private void startTick(MinecraftServer server) {
        this.tickGhostPlayer(server);
    }

    private void endTick(MinecraftServer server) {
        this.ghostDisplayService.tick();
        this.corruptedBlocksService.tick(server);
        this.worldCorruptionService.tick(server);
        this.fakeRestoreService.tick(server);
        this.animalMoveManager.tick(server);
        this.tickPerformanceCollapse();
    }

    private void tickGhostPlayer(MinecraftServer server) {
        boolean bugActive = this.bugState.isActive(GameBreakingBug.GHOST_PLAYER);
        boolean phaseRequested = bugActive && this.bugState.altClipRequested();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.isSpectator()) {
                continue;
            }

            boolean enableNoClip = phaseRequested && this.hasSupportBelow(player);
            player.noClip = enableNoClip;
            player.setNoGravity(enableNoClip);
            if (!enableNoClip) {
                continue;
            }

            var velocity = player.getVelocity();
            player.setVelocity(velocity.x, 0.0D, velocity.z);
        }
    }

    private boolean hasSupportBelow(ServerPlayerEntity player) {
        Box box = player.getBoundingBox();
        double sampleY = box.minY - 0.08D;
        double inset = 0.05D;
        double[] sampleXs = {
                player.getX(),
                box.minX + inset,
                box.maxX - inset
        };
        double[] sampleZs = {
                player.getZ(),
                box.minZ + inset,
                box.maxZ - inset
        };
        for (double sampleX : sampleXs) {
            for (double sampleZ : sampleZs) {
                BlockPos supportPos = BlockPos.ofFloored(sampleX, sampleY, sampleZ);
                if (!player.getEntityWorld().getBlockState(supportPos).getCollisionShape(player.getEntityWorld(), supportPos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void tickPerformanceCollapse() {
        if (this.bugState.isActive(GameBreakingBug.PERFORMANCE_COLLAPSE)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25L));
        }
    }
}
