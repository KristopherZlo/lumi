package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Small native buffers exercise the estimate boundary without allocating a load workload. */
public final class LumiAxiomCaptureGameTests {
    @GameTest(maxTicks = 300000)
    public void axiomBufferAtCaptureLimitKeepsLiveHistory(GameTestHelper helper) {
        new Scenario(helper, false).run();
    }

    @GameTest(maxTicks = 300000)
    public void axiomBufferAboveCaptureLimitKeepsDurableHistory(GameTestHelper helper) {
        new Scenario(helper, true).run();
    }

    @GameTest(maxTicks = 300000)
    public void axiomNoOpAboveCaptureLimitDoesNotCreateBuilderChanges(GameTestHelper helper) {
        unchangedAboveLimit(helper, false);
    }

    @GameTest(maxTicks = 300000)
    public void axiomDeniedNbtAboveCaptureLimitDoesNotCreateBuilderChanges(GameTestHelper helper) {
        unchangedAboveLimit(helper, true);
    }

    private static void unchangedAboveLimit(GameTestHelper helper, boolean deniedNbt) {
        UUID lease = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                .thenExecute(() -> {
                    var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
                    var player = helper.makeMockServerPlayerInLevel();
                    BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
                    helper.getLevel().setBlockAndUpdate(position,
                            (deniedNbt ? Blocks.CHEST : Blocks.STONE).defaultBlockState());
                    try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                        helper.getLevel().setBlockAndUpdate(position.east(2), Blocks.GOLD_BLOCK.defaultBlockState());
                    }
                    var undoBefore = runtime.liveActions().prepareUndo(player.getUUID());
                    helper.assertTrue(undoBefore.isPresent(), "Missing earlier live action");
                    var before = runtime.mutations().builderSnapshot();
                    BlockBuffer buffer = new BlockBuffer() {
                        @Override public int estimateSizeInRAM() { return (64 << 20) + 1; }
                    };
                    buffer.set(position.getX(), position.getY(), position.getZ(), helper.getLevel().getBlockState(position));
                    if (deniedNbt) {
                        helper.assertFalse(com.moulberry.axiom.AxiomServer.canUseAxiom(player,
                                        com.moulberry.axiom.restrictions.AxiomPermission.BUILD_NBT),
                                "Denied fixture unexpectedly has Axiom NBT permission");
                        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(position);
                        var tag = chest.saveWithFullMetadata(helper.getLevel().registryAccess());
                        tag.putString("CustomName", "{\"text\":\"Denied edit\"}");
                        buffer.putBlockEntity(position.getX(), position.getY(), position.getZ(),
                                com.moulberry.axiom.world_modification.CompressedBlockEntity.compress(
                                        tag, new java.io.ByteArrayOutputStream()));
                    }
                    AxiomServerboundSetBuffer.applyBlockBufferServer(buffer, helper.getLevel(), null, player);
                    helper.assertValueEqual(before, runtime.mutations().builderSnapshot(),
                            "No-op capture fallback created a false builder change");
                    helper.assertTrue(runtime.liveActions().lastUnavailableReason(player.getUUID()).isEmpty(),
                            "No-op capture fallback disabled live Undo");
                    helper.assertValueEqual(undoBefore, runtime.liveActions().prepareUndo(player.getUUID()),
                            "Unchanged fallback invalidated an earlier live action");
                    helper.getLevel().getServer().getPlayerList().remove(player);
                    LumiGameTestLease.release(lease);
                })
                .thenSucceed();
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final FabricDimensionRuntime runtime;
        private final boolean fallback;
        private final UUID lease = UUID.randomUUID();
        private final List<BlockPos> positions;
        private ServerPlayer player;
        private CommitAuthor author;
        private UUID zone;
        private DimensionMutation operation;
        private CommitId baselineCommit;
        private CommitId editedCommit;
        private Map<SectionKey, SectionBlob> baseline;
        private Map<SectionKey, SectionBlob> edited;

        private Scenario(GameTestHelper helper, boolean fallback) {
            this.helper = helper;
            this.fallback = fallback;
            runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
            positions = List.of(new BlockPos(2, 2, 2), new BlockPos(3, 2, 2), new BlockPos(2, 18, 2))
                    .stream().map(helper::absolutePos).toList();
        }

        private void run() {
            helper.startSequence()
                    .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                    .thenExecute(this::initialize)
                    .thenWaitUntil(this::awaitIdle)
                    .thenExecute(this::editAndSave)
                    .thenWaitUntil(this::awaitIdle)
                    .thenExecute(() -> {
                        succeeded();
                        editedCommit = activeCommit();
                        restore(baselineCommit);
                    })
                    .thenWaitUntil(this::awaitIdle)
                    .thenExecute(() -> {
                        succeeded();
                        helper.assertValueEqual(baseline, capture(), "Fallback lost the exact section origin or chest NBT");
                        restore(editedCommit);
                    })
                    .thenWaitUntil(this::awaitIdle)
                    .thenExecute(() -> {
                        succeeded();
                        helper.assertValueEqual(edited, capture(), "Fallback Save/Restore left incorrect blocks or block entities");
                        helper.getLevel().getServer().getPlayerList().remove(player);
                        LumiGameTestLease.release(lease);
                    })
                    .thenSucceed();
        }

        private void initialize() {
            player = helper.makeMockServerPlayerInLevel();
            author = new CommitAuthor(player.getUUID(), "Axiom capture boundary");
            try {
                zone = runtime.createZone("Axiom capture boundary", author.id()).id();
                try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), UUID.randomUUID())) {
                    for (BlockPos position : positions) {
                        runtime.growZoneForActor(zone, author.id(), MinecraftSectionCapture.key(position));
                        helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
                    }
                    helper.getLevel().setBlockAndUpdate(positions.getFirst(), Blocks.CHEST.defaultBlockState());
                    ((ChestBlockEntity) helper.getLevel().getBlockEntity(positions.getFirst()))
                            .setItem(0, new ItemStack(Items.DIAMOND, 3));
                }
                baseline = capture();
                save("Capture boundary baseline");
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }

        private void editAndSave() {
            succeeded();
            baselineCommit = activeCommit();
            BlockBuffer buffer = new BlockBuffer() {
                @Override public int estimateSizeInRAM() { return (64 << 20) + (fallback ? 1 : 0); }
            };
            for (BlockPos position : positions) {
                buffer.set(position.getX(), position.getY(), position.getZ(),
                        position.equals(positions.getFirst()) ? Blocks.AIR.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState());
            }
            var before = runtime.mutations().builderSnapshot();
            AxiomServerboundSetBuffer.applyBlockBufferServer(buffer, helper.getLevel(), null, player);
            helper.assertFalse(before.equals(runtime.mutations().builderSnapshot()), "Axiom fallback lost builder attribution");
            for (BlockPos position : positions) {
                helper.assertTrue(runtime.mutations().builderSnapshot().generations()
                                .containsKey(MinecraftSectionCapture.key(position)),
                        "Axiom fallback omitted a builder section");
            }
            helper.assertTrue(runtime.liveActions().prepareUndo(author.id()).isEmpty() == fallback,
                    "Capture boundary offered an incorrect or partial live Undo");
            if (fallback) {
                helper.assertTrue(runtime.liveActions().lastUnavailableReason(author.id())
                                .filter(reason -> reason.contains("capture limit")).isPresent(),
                        "Capture fallback did not explain why live Undo is unavailable");
            }
            helper.assertTrue(helper.getLevel().getBlockState(positions.getFirst()).isAir(), "Axiom did not remove the chest");
            for (BlockPos position : positions.subList(1, positions.size())) {
                helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.GOLD_BLOCK), "Axiom omitted a buffer section");
            }
            edited = capture();
            save("Capture boundary edited");
        }

        private void save(String message) {
            try {
                operation = runtime.startZoneSave(runtime.activeRef(), author, author.id(), zone,
                        message, VersionTags.empty(), false, ignored -> { });
            } catch (IOException failed) { throw new UncheckedIOException(failed); }
        }

        private void restore(CommitId target) {
            try { operation = runtime.startZoneRestore(target, zone, author, ignored -> { }); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
        }

        private CommitId activeCommit() {
            try { return runtime.activeRef().commit(); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
        }

        private Map<SectionKey, SectionBlob> capture() {
            Map<SectionKey, SectionBlob> captured = new LinkedHashMap<>();
            for (BlockPos position : positions) {
                SectionKey key = MinecraftSectionCapture.key(position);
                try {
                    captured.put(key, new MinecraftSectionCapture().capture(helper.getLevel(),
                            helper.getLevel().getChunk(key.chunkX(), key.chunkZ()), key.sectionY()));
                } catch (IOException failed) { throw new UncheckedIOException(failed); }
            }
            return Map.copyOf(captured);
        }

        private void awaitIdle() {
            helper.assertTrue(operation != null && operation.isTerminal()
                            && !runtime.operations().hasActiveOperation() && runtime.operations().queuedCount() == 0,
                    "Axiom capture boundary operation is still active");
        }

        private void succeeded() {
            helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.terminalState(),
                    "Axiom capture boundary history operation failed");
        }
    }
}
