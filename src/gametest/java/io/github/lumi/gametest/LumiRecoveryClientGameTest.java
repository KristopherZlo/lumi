package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Reopens one exact APPLYING fixture and resumes it through the player recovery UI. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiRecoveryClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 12_000;
    private static final int PACKET_TIMEOUT_TICKS = 200;
    private static final String SAVE_NAME = "recovery baseline";
    private static final String INTERRUPTED_STATE_NAME = "recovery interrupted state";

    @Override
    public void runTest(ClientGameTestContext context) {
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
            context.waitTicks(2);
            require(actions.hasEntity(armorStand),
                    "Recovery fixture armor stand was not created");
            baseline = saveThroughUi(
                    context, ui,
                    new LumiClientOperationAwaiter(
                            context, world.getServer(), TIMEOUT_TICKS),
                    world.getServer(), report, SAVE_NAME,
                    "save_recovery_baseline", 1);
            ui.completeOnboardingIfShown();
            new LumiPlayerPacketTestDriver(
                    context, world.getServer(), report, PACKET_TIMEOUT_TICKS)
                    .assertBreakAndPlace(
                            "player_packets_before_recovery", positions.getFirst());
            actions.destroyBlocks("recovery_fixture_break", positions);
            actions.attackEntity(
                    "recovery_fixture_remove_entity", armorStand, Items.DIAMOND_SWORD);
            context.waitTicks(2);
            require(!actions.hasEntity(armorStand),
                    "Recovery fixture armor stand was not removed");
            interruptedState = saveThroughUi(
                    context, ui,
                    new LumiClientOperationAwaiter(
                            context, world.getServer(), TIMEOUT_TICKS),
                    world.getServer(), report, INTERRUPTED_STATE_NAME,
                    "save_recovery_interrupted_state", 2);
            repository = world.getServer().computeOnServer(
                    minecraft -> runtime(minecraft).repository());
        }

        assertCleanReopen(context, worldSave, repository, interruptedState, report,
                "clean_exit_before_fixture");
        OperationJournal interrupted = createApplyingFixture(
                repository, interruptedState, baseline);
        report.event("fixture", "interrupted_restore", "created", 0, 0,
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
                    "Runtime did not expose the APPLYING fixture to recovery UI");

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

            assertRecoveredBlocks(recovered.getServer(), positions);
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
            String operationName,
            int expectedManualVersions) throws IOException {
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
                .filter(snapshot -> snapshot.versions().stream().filter(version ->
                                version.kind() == CommitKind.MANUAL).count()
                                == expectedManualVersions
                        && snapshot.versions().stream().filter(version ->
                                version.kind() == CommitKind.HIDDEN_SAFETY).count() == 1
                        && snapshot.versions().stream().anyMatch(version ->
                                version.id().equals(saved.commit())
                                        && version.kind() == CommitKind.MANUAL
                                        && version.message().equals(saveName)))
                .isPresent(), "Dashboard did not contain exactly "
                        + expectedManualVersions + " manual saves, one Initial, including "
                        + saveName);
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

    private static OperationJournal createApplyingFixture(
            Path repository, BranchRef checkpoint, BranchRef baseline) throws IOException {
        require(checkpoint.name().equals(baseline.name()),
                "Recovery fixture commits belong to different branches");
        OperationTarget target = new OperationTarget(
                checkpoint.name(), checkpoint.commit(), checkpoint.revision(),
                Optional.of(baseline.commit()), Optional.of(checkpoint.commit()));
        OperationJournal journal = new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.APPLYING, target);
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

    private static void assertRecoveredBlocks(
            TestServerContext server, List<BlockPos> positions) {
        List<String> mismatches = server.computeOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            return positions.stream()
                    .filter(position -> !level.getBlockState(position).is(Blocks.STONE))
                    .map(position -> position + "=" + level.getBlockState(position))
                    .toList();
        });
        require(mismatches.isEmpty(),
                "Recovery did not restore every fixture block: " + mismatches);
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
