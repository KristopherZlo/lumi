package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelData;

/** Reopens one persisted pre-publication crash copy through the player recovery UI. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiRecoveryClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 12_000;
    private static final int PACKET_TIMEOUT_TICKS = 200;
    private static final String SAVE_NAME = "recovery baseline";
    private static final String INTERRUPTED_STATE_NAME = "recovery interrupted state";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (LumiClientBehaviorWorld.firstMinuteOnly()) return;
        try (LumiBehaviorReport report = LumiBehaviorReport.create(
                FabricLoader.getInstance().getGameDir(), "recovery")) {
            run(context, report);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot run Lumi recovery behavior test", failed);
        }
    }

    private static void run(ClientGameTestContext context, LumiBehaviorReport report)
            throws IOException {
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        TestWorldSave worldSave;
        Path repository;
        BranchRef baseline;
        BranchRef interruptedState;
        List<BlockPos> positions;
        UUID armorStand;
        BlockPos chestPosition;
        BlockPos poiPosition;
        BlockPos lightPosition;
        BlockPos respawnPosition;
        int expectedBlockLight;

        long createdAt = System.nanoTime();
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    LumiClientBehaviorWorld.configureWorld(settings);
                    settings.setName("Lumi recovery");
                })
                .create()) {
            worldSave = world.getWorldSave();
            closeIncidentalScreen(context);
            world.getClientWorld().waitForChunksDownload();
            awaitHistory(context);
            report.event("stage", "world_create", "succeeded", 0,
                    elapsedMillis(createdAt), worldSave.getSaveDirectory().toString());

            LumiBehaviorActions actions = new LumiBehaviorActions(world.getServer(), report);
            positions = sectionPositions(world.getServer(), actions);
            actions.placeBlocks("recovery_fixture_place", Items.STONE, positions);
            armorStand = actions.placeArmorStands(
                    List.of(positions.get(1).above())).getFirst();
            chestPosition = positions.get(2).above();
            poiPosition = positions.get(3).above();
            lightPosition = positions.get(4).above();
            respawnPosition = positions.get(5).above();
            actions.placeBlocks(
                    "recovery_fixture_chest", Items.CHEST, List.of(chestPosition));
            fillChest(world.getServer(), chestPosition);
            actions.placeBlocks(
                    "recovery_fixture_poi", Items.LECTERN, List.of(poiPosition));
            actions.placeBlocks(
                    "recovery_fixture_light", Items.GLOWSTONE, List.of(lightPosition));
            setRespawn(world.getServer(), respawnPosition);
            context.waitTicks(2);
            expectedBlockLight = blockLight(world.getServer(), lightPosition);
            require(expectedBlockLight > 0,
                    "Recovery fixture did not create block lighting");
            require(actions.hasEntity(armorStand),
                    "Recovery fixture armor stand was not created");
            baseline = saveThroughUi(
                    context, ui,
                    new LumiClientOperationAwaiter(
                            context, world.getServer(), TIMEOUT_TICKS),
                    world.getServer(), report, SAVE_NAME,
                    "save_recovery_baseline");
            ui.completeOnboardingIfShown();
            new LumiPlayerPacketTestDriver(
                    context, world.getServer(), report, PACKET_TIMEOUT_TICKS)
                    .assertBreakAndPlace(
                            "player_packets_before_recovery", positions.getFirst());
            actions.destroyBlocks("recovery_fixture_break", positions);
            actions.destroyBlocks("recovery_fixture_break_special",
                    List.of(chestPosition, poiPosition, lightPosition));
            actions.attackEntity(
                    "recovery_fixture_remove_entity", armorStand, Items.DIAMOND_SWORD);
            setRespawn(world.getServer(), positions.get(6).above());
            context.waitTicks(2);
            require(!actions.hasEntity(armorStand),
                    "Recovery fixture armor stand was not removed");
            interruptedState = saveThroughUi(
                    context, ui,
                    new LumiClientOperationAwaiter(
                            context, world.getServer(), TIMEOUT_TICKS),
                    world.getServer(), report, INTERRUPTED_STATE_NAME,
                    "save_recovery_interrupted_state");
            repository = world.getServer().computeOnServer(
                    minecraft -> runtime(minecraft).repository());
        }

        assertCleanReopen(context, worldSave, repository, interruptedState, report,
                "clean_exit_before_fixture");
        OperationJournal interrupted;
        try (LumiCrashCopy crash = LumiCrashCopy.create(
                worldSave.getSaveDirectory())) {
            crash.captureRefs(repository);
            createPersistedCrashCopy(
                    context, worldSave, baseline, crash, report);
            crash.install(worldSave.getSaveDirectory(), repository);
            interrupted = createWorldPersistedFixture(
                    repository, interruptedState, baseline);
        }
        report.event("fixture", "world_persisted_restore", "created", 0, 0,
                interrupted.operationId().toString());

        BranchRef recoveredRef;
        long reopenedAt = System.nanoTime();
        try (TestSingleplayerContext recovered = worldSave.open()) {
            context.waitForScreen(LumiRecoveryScreen.class);
            report.event("stage", "recovery_prompt", "succeeded", 0,
                    elapsedMillis(reopenedAt), LumiRecoveryScreen.class.getSimpleName());
            require(runtimeFrozen(recovered.getServer()),
                    "Startup recovery did not retain the dimension freeze");
            require(recoveryJournal(recovered.getServer())
                            .filter(interrupted::equals).isPresent(),
                    "Runtime did not expose the WORLD_PERSISTED fixture to recovery UI");

            LumiClientOperationAwaiter operations = new LumiClientOperationAwaiter(
                    context, recovered.getServer(), TIMEOUT_TICKS);
            Set<UUID> previousEvents = operations.eventIds();
            long recoveryAt = System.nanoTime();
            ui.resumeRecovery();
            OperationEventPayload terminal = operations.awaitSuccess(
                    previousEvents, "recovery_resume");
            operations.awaitReleased("recovery_resume");
            report.event("operation", "recovery_resume", "succeeded", 0,
                    elapsedMillis(recoveryAt), terminal.message());

            assertRecoveredWorld(
                    recovered.getServer(), positions, chestPosition, poiPosition,
                    lightPosition, expectedBlockLight, respawnPosition);
            LumiBehaviorActions actions = new LumiBehaviorActions(
                    recovered.getServer(), report);
            require(actions.hasEntity(armorStand),
                    "Recovery did not restore the exact fixture armor stand");
            require(!runtimeFrozen(recovered.getServer()),
                    "Recovery succeeded but retained the dimension freeze");
            require(recoveryJournal(recovered.getServer()).isEmpty(),
                    "Recovery succeeded but runtime still exposes a journal");
            require(new OperationJournalRepository(repository).read().isEmpty(),
                    "Recovery succeeded but active.bin still exists");

            recoveredRef = recovered.getServer().computeOnServer(
                    minecraft -> runtime(minecraft).activeRef());
            BranchRef expectedRecovered = new BranchRef(
                    interruptedState.name(), baseline.commit(),
                    interruptedState.revision() + 1);
            require(recoveredRef.equals(expectedRecovered),
                    "Recovery published an unexpected HEAD: expected="
                            + expectedRecovered + ", actual=" + recoveredRef);
            require(terminal.head().equals(recoveredRef.commit())
                            && terminal.revision() == recoveredRef.revision(),
                    "Recovery terminal event does not match the active ref");
            new LumiPlayerPacketTestDriver(
                    context, recovered.getServer(), report, PACKET_TIMEOUT_TICKS)
                    .assertBreakAndPlace(
                            "player_packets_after_recovery", positions.getFirst());
        }

        assertCleanReopen(context, worldSave, repository, recoveredRef, report,
                "clean_exit_after_recovery");
    }

    private static List<BlockPos> sectionPositions(
            TestServerContext server, LumiBehaviorActions actions) {
        BlockPos origin = server.computeOnServer(minecraft ->
                minecraft.getPlayerList().getPlayers().getFirst().blockPosition());
        int originChunkX = Math.floorDiv(origin.getX(), 16);
        int originChunkZ = Math.floorDiv(origin.getZ(), 16);
        List<BlockPos> positions = new ArrayList<>(64);
        positions.add(actions.surfacePosition(
                originChunkX * 16 + 8, originChunkZ * 16 + 8));
        for (int chunkZ = originChunkZ - 4; chunkZ < originChunkZ + 4; chunkZ++) {
            for (int chunkX = originChunkX - 4; chunkX < originChunkX + 4; chunkX++) {
                if (chunkX != originChunkX || chunkZ != originChunkZ) {
                    positions.add(actions.surfacePosition(
                            chunkX * 16 + 8, chunkZ * 16 + 8));
                }
            }
        }
        return List.copyOf(positions);
    }

    private static BranchRef saveThroughUi(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            LumiClientOperationAwaiter operations,
            TestServerContext server,
            LumiBehaviorReport report,
            String saveName,
            String operationName) throws IOException {
        BranchRef before = server.computeOnServer(minecraft -> runtime(minecraft).activeRef());
        Set<UUID> previousEvents = operations.eventIds();
        long started = System.nanoTime();
        ui.save(saveName);
        OperationEventPayload terminal = operations.awaitSuccess(
                previousEvents, operationName);
        operations.awaitReleased(operationName);
        BranchRef saved = server.computeOnServer(minecraft -> runtime(minecraft).activeRef());
        require(terminal.head().equals(saved.commit())
                        && terminal.revision() == saved.revision(),
                "Save terminal event does not match the active ref");
        require(saved.revision() > before.revision()
                        && !saved.commit().equals(before.commit()),
                "UI Save did not advance the active ref");
        await(context, client -> LumiClient.history().state().snapshot()
                .filter(snapshot -> snapshot.head().equals(saved.commit())
                        && snapshot.revision() == saved.revision())
                .isPresent(), "Client snapshot did not advance to " + saveName);
        awaitIsometricPreview(context, server, saved.commit());
        report.event("operation", operationName, "succeeded", 0,
                elapsedMillis(started), saved.commit().hex());
        return saved;
    }

    private static void awaitIsometricPreview(
            ClientGameTestContext context,
            TestServerContext server,
            io.github.lumi.domain.model.CommitId commit) throws IOException {
        Path repository = server.computeOnServer(
                minecraft -> runtime(minecraft).repository());
        VersionPreviewRepository previews = new VersionPreviewRepository(repository);
        byte[] png = null;
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (tick % 10 == 0) {
                png = previews.load(commit).orElse(null);
                if (png != null) break;
            }
            context.waitTick();
        }
        require(png != null, "Save did not publish an isometric preview for " + commit);
        try (NativeImage image = NativeImage.read(png)) {
            boolean transparent = false;
            boolean opaque = false;
            boolean neutralOrWarm = false;
            for (int pixel : image.getPixelsABGR()) {
                int alpha = pixel >>> 24;
                transparent |= alpha == 0;
                opaque |= alpha != 0;
                neutralOrWarm |= alpha != 0
                        && (pixel & 0xff) >= ((pixel >>> 16) & 0xff);
            }
            require(image.getWidth() > 1 && image.getHeight() > 1
                            && transparent && opaque && neutralOrWarm,
                    "Save preview is not a cropped transparent isometric render");
        }
    }

    private static void createPersistedCrashCopy(
            ClientGameTestContext context,
            TestWorldSave worldSave,
            BranchRef baseline,
            LumiCrashCopy crash,
            LumiBehaviorReport report) throws IOException {
        long started = System.nanoTime();
        try (TestSingleplayerContext restored = worldSave.open()) {
            awaitHistory(context);
            AtomicReference<Throwable> copyFailure = new AtomicReference<>();
            AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();
            restored.getServer().computeOnServer(minecraft -> {
                FabricDimensionRuntime runtime = runtime(minecraft);
                runtime.operations().observeNextEnqueue((ticket, ignored) ->
                        runtime.operations().observeTerminal(ticket, completed -> {
                            if (completed.terminalState()
                                    != MutationTerminalState.SUCCEEDED) {
                                copyFailure.compareAndSet(null, new AssertionError(
                                        "Crash-copy Restore did not succeed"));
                                return;
                            }
                            try {
                                crash.captureWorld(worldSave.getSaveDirectory());
                            } catch (IOException failed) {
                                copyFailure.compareAndSet(null, failed);
                            }
                        }));
                ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
                runtime.startRestore(
                        baseline.commit(),
                        new CommitAuthor(player.getUUID(), "Recovery crash gate"),
                        operation -> terminal.set(operation.terminalState()));
                return null;
            });
            awaitTerminal(context, terminal, "crash_copy_restore");
            new LumiClientOperationAwaiter(
                    context, restored.getServer(), TIMEOUT_TICKS)
                    .awaitReleased("crash_copy_restore");
            throwCopyFailure(copyFailure.get());
        }
        report.event("fixture", "world_persisted_copy", "captured", 0,
                elapsedMillis(started), worldSave.getSaveDirectory().toString());
    }

    private static void awaitTerminal(
            ClientGameTestContext context,
            AtomicReference<MutationTerminalState> terminal,
            String name) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            MutationTerminalState state = terminal.get();
            if (state != null) {
                require(state == MutationTerminalState.SUCCEEDED,
                        name + " ended as " + state);
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(name + " did not settle within "
                + TIMEOUT_TICKS + " ticks");
    }

    private static void throwCopyFailure(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw new IOException("Cannot capture Restore crash copy", failure);
    }

    private static OperationJournal createWorldPersistedFixture(
            Path repository, BranchRef checkpoint, BranchRef baseline) throws IOException {
        require(checkpoint.name().equals(baseline.name()),
                "Recovery fixture commits belong to different branches");
        BranchRef current = new BranchRefRepository(repository)
                .read(checkpoint.name()).orElseThrow();
        require(current.equals(checkpoint),
                "Crash copy did not restore the pre-publication branch ref");
        OperationTarget target = new OperationTarget(
                checkpoint.name(), checkpoint.commit(), checkpoint.revision(),
                Optional.of(baseline.commit()), Optional.of(checkpoint.commit()));
        OperationJournal journal = new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE,
                OperationPhase.WORLD_PERSISTED, target);
        OperationJournalRepository journals = new OperationJournalRepository(repository);
        require(journals.read().isEmpty(),
                "Clean world exit left an operation journal before fixture creation");
        return journals.create(journal);
    }

    private static void assertCleanReopen(
            ClientGameTestContext context,
            TestWorldSave worldSave,
            Path repository,
            BranchRef expected,
            LumiBehaviorReport report,
            String name) throws IOException {
        long started = System.nanoTime();
        try (TestSingleplayerContext clean = worldSave.open()) {
            awaitHistory(context);
            for (int tick = 0; tick < 40; tick++) {
                require(!context.computeOnClient(
                                client -> client.screen instanceof LumiRecoveryScreen),
                        name + " displayed LumiRecoveryScreen");
                require(!context.computeOnClient(client -> LumiClient.history().state()
                                .snapshot().orElseThrow().recoveryPending()),
                        name + " published recoveryPending=true");
                context.waitTick();
            }
            BranchRef active = clean.getServer().computeOnServer(
                    minecraft -> runtime(minecraft).activeRef());
            require(active.equals(expected), name + " changed the active ref");
            require(recoveryJournal(clean.getServer()).isEmpty(),
                    name + " produced a runtime recovery journal");
            require(!runtimeFrozen(clean.getServer()),
                    name + " froze the dimension");
            require(new OperationJournalRepository(repository).read().isEmpty(),
                    name + " produced active.bin");
        }
        report.event("assertion", name, "succeeded", 40,
                elapsedMillis(started), "");
    }

    private static void assertRecoveredWorld(
            TestServerContext server,
            List<BlockPos> positions,
            BlockPos chestPosition,
            BlockPos poiPosition,
            BlockPos lightPosition,
            int expectedBlockLight,
            BlockPos respawnPosition) {
        List<String> mismatches = server.computeOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            return positions.stream()
                    .filter(position -> !level.getBlockState(position).is(Blocks.STONE))
                    .map(position -> position + "=" + level.getBlockState(position))
                    .toList();
        });
        require(mismatches.isEmpty(),
                "Recovery did not restore every fixture block: " + mismatches);
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            var level = player.level();
            var blockEntity = level.getBlockEntity(chestPosition);
            require(blockEntity instanceof ChestBlockEntity chest
                            && chest.getItem(0).is(Items.DIAMOND)
                            && chest.getItem(0).getCount() == 7,
                    "Recovery did not restore exact chest contents");
            var poi = level.getChunkSource().getPoiManager().getType(poiPosition);
            require(poi.equals(PoiTypes.forState(level.getBlockState(poiPosition)))
                            && poi.isPresent(),
                    "Recovery did not restore the lectern POI");
            require(level.getBrightness(
                            LightLayer.BLOCK, lightPosition.relative(Direction.EAST))
                            == expectedBlockLight,
                    "Recovery did not restore exact block lighting");
            var respawn = player.getRespawnConfig();
            require(respawn != null
                            && respawn.respawnData().dimension().equals(level.dimension())
                            && respawn.respawnData().pos().equals(respawnPosition)
                            && respawn.respawnData().yaw() == 0
                            && respawn.respawnData().pitch() == 0
                            && respawn.forced(),
                    "Recovery did not restore player respawn data");
        });
    }

    private static int blockLight(TestServerContext server, BlockPos lightPosition) {
        return server.computeOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            return level.getBrightness(
                    LightLayer.BLOCK, lightPosition.relative(Direction.EAST));
        });
    }

    private static void fillChest(TestServerContext server, BlockPos position) {
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            var blockEntity = level.getBlockEntity(position);
            require(blockEntity instanceof ChestBlockEntity,
                    "Recovery fixture chest has no block entity");
            ChestBlockEntity chest = (ChestBlockEntity) blockEntity;
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            chest.setChanged();
        });
    }

    private static void setRespawn(TestServerContext server, BlockPos position) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            var data = LevelData.RespawnData.of(
                    player.level().dimension(), position, 0, 0);
            player.setRespawnPosition(
                    new ServerPlayer.RespawnConfig(data, true), false);
        });
    }

    private static Optional<OperationJournal> recoveryJournal(TestServerContext server) {
        return server.computeOnServer(minecraft -> runtime(minecraft).recoveryJournal());
    }

    private static boolean runtimeFrozen(TestServerContext server) {
        return server.computeOnServer(minecraft -> runtime(minecraft).freeze().isFrozen());
    }

    private static FabricDimensionRuntime runtime(MinecraftServer minecraft) {
        var level = minecraft.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow(
                () -> new AssertionError("Lumi runtime is not loaded"));
    }

    private static void awaitHistory(ClientGameTestContext context) {
        await(context, client -> LumiClient.history().state().snapshot().isPresent(),
                "Lumi history did not synchronize");
    }

    private static void closeIncidentalScreen(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitForScreen(null);
    }

    private static void await(
            ClientGameTestContext context,
            java.util.function.Predicate<net.minecraft.client.Minecraft> predicate,
            String failure) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (context.computeOnClient(predicate::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(failure + " within " + TIMEOUT_TICKS + " ticks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
