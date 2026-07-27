package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exhaustive player-driven recovery gates for the bundled redstone machines. */
public final class LumiRedstoneMachineGameTests {
    private static final int FIXTURE_UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    @GameTest(
            structure = "minecraft:redstone-test-rgmachine.1",
            setupTicks = 20,
            maxTicks = 300000,
            skyAccess = true)
    public void rgMachineOne(GameTestHelper helper) {
        new Scenario(helper, new BlockPos(8, 3, 4),
                new BlockPos(0, 1, 1), 40).run();
    }

    @GameTest(
            structure = "minecraft:redstone-test-rgmachine.2",
            setupTicks = 20,
            maxTicks = 300000,
            skyAccess = true)
    public void rgMachineTwo(GameTestHelper helper) {
        new Scenario(helper, new BlockPos(20, 15, 12),
                new BlockPos(1, 1, 9), 245).run();
    }

    private enum Recovery {
        QUICK_RESTORE,
        RESTORE,
        UNDO
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final FabricDimensionRuntime runtime;
        private final BlockPos size;
        private final BlockPos relativeButton;
        private final int finalDelay;
        private final UUID lease = UUID.randomUUID();

        private ServerPlayer player;
        private CommitAuthor author;
        private BlockPos button;
        private BlockBox area;
        private CommitId baselineCommit;
        private LumiWorldSnapshot baseline;
        private DimensionMutation current;
        private MutationTerminalState terminal;

        private Scenario(
                GameTestHelper helper,
                BlockPos size,
                BlockPos relativeButton,
                int finalDelay) {
            this.helper = helper;
            runtime = LumiMod.serverRuntime().find(helper.getLevel())
                    .orElseThrow(() -> helper.assertionException(
                            "Lumi runtime is not loaded"));
            this.size = size;
            this.relativeButton = relativeButton;
            this.finalDelay = finalDelay;
        }

        private void run() {
            GameTestSequence sequence = helper.startSequence()
                    .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                    .thenExecute(this::prepare)
                    .thenWaitUntil(this::requireIdle)
                    .thenExecute(this::finishBaselineSave);
            for (Recovery recovery : Recovery.values()) {
                appendAttempt(sequence, recovery, 2);
                for (int delay = 5; delay <= finalDelay; delay += 5) {
                    appendAttempt(sequence, recovery, delay);
                }
            }
            sequence
                    .thenExecute(() -> startRecovery(Recovery.QUICK_RESTORE))
                    .thenWaitUntil(this::requireIdle)
                    .thenExecute(() -> assertRecovered("final cleanup"))
                    .thenExecute(() -> LumiGameTestLease.release(lease))
                    .thenSucceed();
        }

        private void appendAttempt(
                GameTestSequence sequence, Recovery recovery, int delay) {
            sequence
                    .thenExecute(this::pressButton)
                    .thenIdle(delay)
                    .thenExecute(() -> startRecovery(recovery))
                    .thenWaitUntil(this::requireIdle)
                    .thenExecute(() -> assertRecovered(
                            recovery.name().toLowerCase() + " after " + delay + " ticks"));
        }

        private void prepare() {
            helper.assertBlockPresent(Blocks.STONE_BUTTON, relativeButton);
            button = helper.absolutePos(relativeButton);
            BlockPos first = helper.absolutePos(BlockPos.ZERO);
            BlockPos last = helper.absolutePos(size.offset(-1, -1, -1));
            area = new BlockBox(
                    first.getX(), first.getY(), first.getZ(),
                    last.getX(), last.getY(), last.getZ());

            player = helper.makeMockServerPlayerInLevel();
            author = new CommitAuthor(player.getUUID(), "Redstone GameTest");
            Direction facing = helper.getBlockState(relativeButton)
                    .getValue(HorizontalDirectionalBlock.FACING);
            BlockPos stand = button.relative(facing, 2).below();
            player.teleportTo(
                    stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(button));
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            touchStructureSections();
            startBaselineSave();
        }

        private void touchStructureSections() {
            var sections = area.sectionCells(64);
            try (var ignored = DirectLiveActionContext.open(
                    runtime.liveActions(), player.getUUID())) {
                for (SectionKey section : sections) {
                    BlockPos probe = emptyProbe(section);
                    helper.getLevel().setBlock(
                            probe, Blocks.BARRIER.defaultBlockState(), FIXTURE_UPDATE_FLAGS);
                    helper.getLevel().setBlock(
                            probe, Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE_FLAGS);
                }
            }
            helper.assertTrue(
                    runtime.mutations().builderSnapshot().generations()
                            .keySet().containsAll(sections),
                    "GameTest structure did not enter Lumi's builder working index");
        }

        private BlockPos emptyProbe(SectionKey section) {
            int sectionX = section.chunkX() * 16;
            int sectionY = section.sectionY() * 16;
            int sectionZ = section.chunkZ() * 16;
            int minX = Math.max(area.minX(), sectionX);
            int minY = Math.max(area.minY(), sectionY);
            int minZ = Math.max(area.minZ(), sectionZ);
            int maxX = Math.min(area.maxX(), sectionX + 15);
            int maxY = Math.min(area.maxY(), sectionY + 15);
            int maxZ = Math.min(area.maxZ(), sectionZ + 15);
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (helper.getLevel().isEmptyBlock(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
            throw helper.assertionException(
                    "Redstone fixture section has no empty baseline probe: %s", section);
        }

        private void startBaselineSave() {
            terminal = null;
            try {
                current = runtime.startSave(new SaveRequest(
                        runtime.activeRef(), author, "Redstone machine baseline",
                        Instant.now(), runtime.activeWorkspaceId(),
                        Optional.empty(), CommitKind.MANUAL),
                        operation -> terminal = operation.terminalState());
            } catch (IOException failed) {
                throw helper.assertionException(
                        "Cannot save redstone baseline: %s", failed.getMessage());
            }
        }

        private void finishBaselineSave() {
            requireSucceeded("Baseline Save");
            try {
                baselineCommit = runtime.activeRef().commit();
                baseline = LumiWorldSnapshot.capture(
                        helper.getLevel(), List.of(area));
            } catch (IOException failed) {
                throw helper.assertionException(
                        "Cannot capture redstone baseline: %s", failed.getMessage());
            }
        }

        private void pressButton() {
            ItemStack hand = player.getItemInHand(InteractionHand.MAIN_HAND);
            var result = player.gameMode.useItemOn(
                    player, helper.getLevel(), hand, InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(button),
                            helper.getBlockState(relativeButton)
                                    .getValue(HorizontalDirectionalBlock.FACING),
                            button, false));
            helper.assertTrue(result.consumesAction(),
                    "Player could not press the redstone machine button");
        }

        private void startRecovery(Recovery recovery) {
            terminal = null;
            try {
                current = switch (recovery) {
                    case QUICK_RESTORE -> runtime.startQuickRollback(
                            author, operation -> terminal = operation.terminalState());
                    case RESTORE -> runtime.startRestore(
                            baselineCommit, author,
                            operation -> terminal = operation.terminalState());
                    case UNDO -> runtime.startLiveAction(
                            player.getUUID(), LiveActionJournal.Direction.UNDO,
                            operation -> terminal = operation.terminalState());
                };
            } catch (IOException failed) {
                throw helper.assertionException(
                        "Cannot start %s: %s", recovery, failed.getMessage());
            }
        }

        private void requireIdle() {
            String phase = current == null ? "not started" : current.progress().phase();
            helper.assertTrue(
                    current != null
                            && current.isTerminal()
                            && !runtime.operations().hasActiveOperation()
                            && runtime.operations().queuedCount() == 0,
                    "Lumi operation is still active: " + phase);
        }

        private void assertRecovered(String label) {
            requireSucceeded(label);
            try {
                LumiWorldSnapshot.capture(helper.getLevel(), List.of(area))
                        .assertMatches(baseline, label);
            } catch (AssertionError mismatch) {
                throw helper.assertionException("%s", mismatch.getMessage());
            } catch (IOException failed) {
                throw helper.assertionException(
                        "Cannot verify %s: %s", label, failed.getMessage());
            }
        }

        private void requireSucceeded(String operation) {
            helper.assertValueEqual(
                    MutationTerminalState.SUCCEEDED, terminal,
                    operation + " did not succeed");
        }
    }
}
