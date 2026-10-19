package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.packets.AxiomServerboundFixArea;
import com.moulberry.axiom.packets.AxiomServerboundTickBlocks;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Native region entrances; packet transport and integrated dispatcher have separate client coverage. */
public final class LumiAxiomRegionGameTests {
    @GameTest(maxTicks = 300000)
    public void axiomBiomeWriteHonorsFreezeWithoutEnteringHistory(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        var player = new AtomicReference<ServerPlayer>();
        var operation = new AtomicReference<DimensionMutation>();
        var original = new AtomicReference<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>>();
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    original.set(helper.getLevel().getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2).unwrapKey().orElseThrow());
                    var buffer = new BiomeBuffer();
                    buffer.set(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2,
                            original.get().equals(Biomes.DESERT) ? Biomes.PLAINS : Biomes.DESERT);
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        applyBiomes(buffer, helper.getLevel(), player.get());
                        helper.assertTrue(helper.getLevel().getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2).is(original.get()), "Frozen biome write changed the world");
                    } finally { freeze.release(); }
                    applyBiomes(buffer, helper.getLevel(), player.get());
                    helper.assertTrue(!helper.getLevel().getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2).is(original.get()), "Native biome edit was not applied");
                    helper.assertValueEqual(before, runtime.mutations().builderSnapshot(), "Biome edit entered saved-version history");
                    operation.set(runtime.startLiveAction(player.get().getUUID(), LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireSuccess(helper, operation.get(), "Biome Undo"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2).is(original.get()), "Biome Undo mismatch");
                    operation.set(runtime.startLiveAction(player.get().getUUID(), LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireSuccess(helper, operation.get(), "Biome Redo"))
                .thenExecute(() -> {
                    helper.assertTrue(!helper.getLevel().getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2).is(original.get()), "Biome Redo mismatch");
                    operation.set(runtime.startLiveAction(player.get().getUUID(), LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireSuccess(helper, operation.get(), "Biome cleanup Undo"))
                .thenExecute(() -> {
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                }).thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomExplicitTickRetainsFallingBlockCausality(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        BlockPos source = new BlockPos(3, 6, 3);
        BlockPos landing = new BlockPos(3, 2, 3);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<DimensionMutation> operation = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.getLevel().getServer().getPlayerList().op(player.get().nameAndId(),
                            Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    LumiAxiomGameTests.activateAxiom(player.get());
                    helper.setBlock(landing.below(), Blocks.STONE);
                    helper.getLevel().setBlock(helper.absolutePos(source), Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
                    var packet = new AxiomServerboundTickBlocks(helper.getLevel().dimension(),
                            helper.absolutePos(source), helper.absolutePos(source));
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        packet.handle(helper.getLevel().getServer(), player.get());
                        helper.assertBlockState(source, Blocks.SAND.defaultBlockState());
                        helper.assertValueEqual(before, runtime.mutations().builderSnapshot(), "Frozen explicit tick dirtied the draft");
                    } finally {
                        freeze.release();
                    }
                    packet.handle(helper.getLevel().getServer(), player.get());
                })
                .thenWaitUntil(() -> helper.assertBlockState(landing, Blocks.SAND.defaultBlockState()))
                .thenExecute(() -> operation.set(runtime.startLiveAction(player.get().getUUID(),
                        LiveActionJournal.Direction.UNDO, ignored -> { })))
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Tick Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(), "Explicit tick Undo failed");
                    helper.assertBlockState(source, Blocks.SAND.defaultBlockState());
                    helper.assertBlockState(landing, Blocks.AIR.defaultBlockState());
                    LumiAxiomGameTests.deactivateAxiom(player.get());
                    helper.getLevel().getServer().getPlayerList().deop(player.get().nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomPoiRepairHonorsFreezeWithoutInventingContentEdits(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    helper.getLevel().getServer().getPlayerList().op(player.nameAndId(),
                            Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    LumiAxiomGameTests.activateAxiom(player);
                    BlockPos relative = new BlockPos(2, 2, 2);
                    helper.setBlock(relative, Blocks.LECTERN);
                    BlockPos position = helper.absolutePos(relative);
                    var pois = helper.getLevel().getPoiManager();
                    pois.remove(position);
                    var packet = new AxiomServerboundFixArea(helper.getLevel().dimension(), position, position);
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        packet.handle(helper.getLevel().getServer(), player);
                        helper.assertTrue(pois.getType(position).isEmpty(), "Frozen repair changed derived POI state");
                    } finally {
                        freeze.release();
                    }
                    packet.handle(helper.getLevel().getServer(), player);
                    helper.assertValueEqual(PoiTypes.forState(Blocks.LECTERN.defaultBlockState()), pois.getType(position),
                            "Axiom did not repair the lectern POI");
                    helper.assertValueEqual(before, runtime.mutations().builderSnapshot(), "Pure POI repair invented a builder content edit");
                    LumiAxiomGameTests.deactivateAxiom(player);
                    helper.getLevel().getServer().getPlayerList().deop(player.nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player);
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    private static void requireSuccess(GameTestHelper helper, DimensionMutation operation, String label) {
        helper.assertTrue(operation.isTerminal(), label + " has not finished");
        helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.terminalState(), label + " failed: " + operation.failure());
    }

    private static void applyBiomes(BiomeBuffer buffer, ServerLevel level, ServerPlayer player) {
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        UUID dispatch = UUID.randomUUID();
        runtime.axiomBufferActions().begin(player.connection, player.getUUID(), dispatch);
        try {
            io.github.lumi.minecraft.runtime.AxiomBufferDispatchContext.run(player, dispatch, () -> invokeBiomes(buffer, level));
        } finally { runtime.axiomBufferActions().complete(player.connection, dispatch); }
    }

    private static void invokeBiomes(BiomeBuffer buffer, ServerLevel level) {
        try {
            var method = AxiomServerboundSetBuffer.class.getDeclaredMethod("applyBiomeBufferServer", BiomeBuffer.class, ServerLevel.class);
            method.setAccessible(true);
            method.invoke(null, buffer, level);
        } catch (InvocationTargetException failed) {
            throw new IllegalStateException("Native Axiom biome write failed", failed.getCause());
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Native Axiom biome entrypoint changed", failed);
        }
    }
}
