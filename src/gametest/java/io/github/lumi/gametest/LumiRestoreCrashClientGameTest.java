package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Produces and verifies one real process crash selected by the Gradle matrix. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiRestoreCrashClientGameTest implements FabricClientGameTest {
    private static final String MODE_PROPERTY = "lumi.gametest.restoreCrashMode";
    private static final String PHASE_PROPERTY = "lumi.gametest.restoreCrashPhase";
    private static final String EXPECTED_PHASE_PROPERTY =
            "lumi.gametest.restoreCrashExpectedPhase";
    private static final int TIMEOUT_TICKS = 12_000;

    @Override
    public void runTest(ClientGameTestContext context) {
        String mode = System.getProperty(MODE_PROPERTY, "");
        if (mode.isBlank()
                || !LumiClientTestSuite.includes(LumiClientTestSuite.RESTORE_CRASH)) {
            return;
        }
        try {
            if (mode.equals("produce")) {
                produce(context);
            } else if (mode.equals("verify")) {
                verify(context);
            } else {
                throw new IllegalArgumentException("Unknown Restore crash mode: " + mode);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Restore crash matrix failed", failed);
        }
    }

    private static void produce(ClientGameTestContext context) throws IOException {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        LumiBehaviorReport report = LumiBehaviorReport.create(
                gameDirectory, "restore-crash-producer");
        TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    LumiClientBehaviorWorld.configureWorld(settings);
                    settings.setName("Lumi Restore crash matrix");
                })
                .create();
        context.runOnClient(client -> client.options.pauseOnLostFocus = false);
        context.setScreen(() -> null);
        context.waitForScreen(null);
        world.getClientWorld().waitForChunksDownload();
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        LumiBehaviorActions actions = new LumiBehaviorActions(world.getServer(), report);
        LumiBehaviorOperations operations = new LumiBehaviorOperations(
                context, world.getServer(), report);
        List<BlockPos> positions = fixturePositions(world.getServer(), actions);
        actions.placeBlocks("crash_target_blocks", Items.STONE, positions);
        UUID entity = actions.placeArmorStands(
                List.of(positions.getFirst().above())).getFirst();
        CommitId target = operations.save("crash-target");
        actions.destroyBlocks("crash_return_blocks", positions);
        actions.attackEntity("crash_return_entity", entity, Items.DIAMOND_SWORD);
        operations.save("crash-return");
        BranchRef before = world.getServer().computeOnServer(
                minecraft -> runtime(minecraft).activeRef());
        writeMetadata(gameDirectory, world.getWorldSave(), before,
                target, positions, entity);
        world.getServer().computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            try {
                runtime(minecraft).startRestore(target,
                        new CommitAuthor(player.getUUID(), player.getName().getString()));
            } catch (IOException failed) {
                throw new IllegalStateException("Cannot start crash Restore", failed);
            }
            return null;
        });
        context.waitTicks(TIMEOUT_TICKS);
        throw new AssertionError("Restore crash cutpoint was not reached");
    }

    private static void verify(ClientGameTestContext context) throws IOException {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Properties metadata = readMetadata(gameDirectory);
        String expectedPhase = System.getProperty(EXPECTED_PHASE_PROPERTY, "");
        require(expectedPhase.equals(metadata.getProperty("phase")),
                "Crash metadata belongs to another phase");
        TestWorldSave save = new TestWorldSaveImpl(
                context, Path.of(metadata.getProperty("world")));
        try (TestSingleplayerContext recovered = save.open()) {
            context.runOnClient(client -> client.options.pauseOnLostFocus = false);
            context.waitForScreen(LumiRecoveryScreen.class);
            FabricDimensionRuntime runtime = runtime(recovered.getServer());
            require(runtime.freeze().isFrozen(),
                    "Interrupted Restore did not freeze on reopen");
            awaitRecovery(context, recovered.getServer());
            context.setScreen(() -> null);
            context.waitForScreen(null);
            assertTarget(recovered.getServer(), metadata);
        }
        try (TestSingleplayerContext reopened = save.open()) {
            new LumiUiTestDriver(context).awaitHistory();
            for (int tick = 0; tick < 40; tick++) {
                require(!context.computeOnClient(
                                client -> client.screen instanceof LumiRecoveryScreen),
                        "Clean reopen displayed recovery UI");
                context.waitTick();
            }
            assertTarget(reopened.getServer(), metadata);
        }
        Files.deleteIfExists(metadataPath(gameDirectory));
    }

    private static void awaitRecovery(
            ClientGameTestContext context, TestServerContext server) {
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();
        server.computeOnServer(minecraft -> {
            try {
                runtime(minecraft).startRecovery(RecoveryChoice.RESUME_TARGET,
                        operation -> terminal.set(operation.terminalState()));
            } catch (IOException failed) {
                throw new IllegalStateException("Cannot resume crash Restore", failed);
            }
            return null;
        });
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (terminal.get() != null) {
                require(terminal.get() == MutationTerminalState.SUCCEEDED,
                        "Crash Restore recovery ended as " + terminal.get());
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Crash Restore recovery did not settle");
    }

    private static void assertTarget(TestServerContext server, Properties metadata)
            throws IOException {
        List<BlockPos> positions = parsePositions(metadata.getProperty("positions"));
        UUID entity = UUID.fromString(metadata.getProperty("entity"));
        BranchRef expectedBefore = new BranchRef(
                new io.github.lumi.domain.model.BranchName(metadata.getProperty("branch")),
                new CommitId(new io.github.lumi.domain.model.ObjectId(
                        metadata.getProperty("before"))),
                Long.parseLong(metadata.getProperty("revision")));
        CommitId target = new CommitId(new io.github.lumi.domain.model.ObjectId(
                metadata.getProperty("target")));
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            positions.forEach(position -> require(level.getBlockState(position).is(Blocks.STONE),
                    "Recovered block mismatch at " + position));
            require(level.getEntityInAnyDimension(entity) instanceof ArmorStand,
                    "Recovered armor stand is missing");
        });
        FabricDimensionRuntime runtime = runtime(server);
        BranchRef actual = runtime.activeRef();
        require(actual.name().equals(expectedBefore.name())
                        && actual.commit().equals(target)
                        && actual.revision() == expectedBefore.revision() + 1,
                "Recovered ref mismatch: " + actual);
        require(runtime.recoveryJournal().isEmpty() && !runtime.freeze().isFrozen(),
                "Recovered runtime retained journal or freeze");
        require(new OperationJournalRepository(runtime.repository()).read().isEmpty(),
                "Recovered repository retained active.bin");
    }

    private static List<BlockPos> fixturePositions(
            TestServerContext server, LumiBehaviorActions actions) {
        BlockPos origin = server.computeOnServer(minecraft ->
                minecraft.getPlayerList().getPlayers().getFirst().blockPosition());
        List<BlockPos> positions = new ArrayList<>();
        for (int offset = 0; offset < 128; offset += 32) {
            positions.add(actions.surfacePosition(origin.getX() + offset, origin.getZ()));
        }
        return List.copyOf(positions);
    }

    private static void writeMetadata(
            Path gameDirectory, TestWorldSave save, BranchRef before,
            CommitId target, List<BlockPos> positions, UUID entity) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("phase", System.getProperty(PHASE_PROPERTY, ""));
        metadata.setProperty("world", save.getSaveDirectory().toAbsolutePath().toString());
        metadata.setProperty("branch", before.name().value());
        metadata.setProperty("before", before.commit().hex());
        metadata.setProperty("revision", Long.toString(before.revision()));
        metadata.setProperty("target", target.hex());
        metadata.setProperty("entity", entity.toString());
        metadata.setProperty("positions", positions.stream()
                .map(position -> position.getX() + "," + position.getY()
                        + "," + position.getZ())
                .collect(java.util.stream.Collectors.joining(";")));
        Path path = metadataPath(gameDirectory);
        Files.createDirectories(path.getParent());
        try (FileChannel file = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            metadata.store(Channels.newOutputStream(file), null);
            file.force(true);
        }
    }

    private static Properties readMetadata(Path gameDirectory) throws IOException {
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(metadataPath(gameDirectory))) {
            metadata.load(input);
        }
        return metadata;
    }

    private static List<BlockPos> parsePositions(String value) {
        return java.util.Arrays.stream(value.split(";")).map(encoded -> {
            int[] coordinate = java.util.Arrays.stream(encoded.split(","))
                    .mapToInt(Integer::parseInt).toArray();
            return new BlockPos(coordinate[0], coordinate[1], coordinate[2]);
        }).toList();
    }

    private static Path metadataPath(Path gameDirectory) {
        return gameDirectory.resolve("lumi-restore-crash.properties");
    }

    private static FabricDimensionRuntime runtime(TestServerContext server) {
        return server.computeOnServer(LumiRestoreCrashClientGameTest::runtime);
    }

    private static FabricDimensionRuntime runtime(net.minecraft.server.MinecraftServer server) {
        var level = server.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
