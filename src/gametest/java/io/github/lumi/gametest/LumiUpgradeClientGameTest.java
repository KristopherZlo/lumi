package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.HistoryGenerationConversionService;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.world.MinecraftWorldPayloadConverter;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.HistoryGenerationRepository;
import io.github.lumi.storage.repository.HistorySourceSnapshot;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

/** Stopped-world upgrade oracle: vanilla copies supply expected states independently of history conversion. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiUpgradeClientGameTest implements FabricClientGameTest {
    private static final List<BlockBox> AREA = List.of(new BlockBox(0, 176, 0, 15, 191, 15));
    private static final BlockPos CHEST = new BlockPos(2, 177, 2);
    private final Properties fixture = new Properties();
    private Path directory;
    private LumiWorldSnapshot expectedSaved;
    private LumiWorldSnapshot expectedWorking;
    private MinecraftWorldPayloadConverter converter;
    private HistoryGenerationConversionService.Phase restartPhase;

    @Override public void runTest(ClientGameTestContext context) {
        String stage = System.getProperty("lumi.gametest.upgrade.stage", "");
        if (stage.isEmpty()) return;
        if (stage.equals("resume")) restartPhase = HistoryGenerationConversionService.Phase.valueOf(
                System.getProperty("lumi.gametest.upgrade.expectedCut"));
        directory = Path.of(System.getProperty("lumi.gametest.upgrade.fixture")).toAbsolutePath();
        var freezeSimulation = new AtomicBoolean(true);
        // The independent copies must not advance ticking block-entity fields by different amounts.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (freezeSimulation.get()) server.tickRateManager().setFrozen(true);
        });
        var acceptUpgradeDone = new AtomicBoolean(stage.equals("verify") || stage.equals("startup") || stage.equals("resume"));
        context.runOnClient(client -> ClientTickEvents.END_CLIENT_TICK.register(ticking -> {
            if (acceptUpgradeDone.get() && ticking.screen instanceof ConfirmScreen screen
                    && screen.getTitle().getString().equals(Component.translatable("upgradeWorld.done").getString())) {
                screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.active && button.getMessage().getString()
                                .equals(CommonComponents.GUI_YES.getString()))
                        .findFirst().ifPresent(button -> button.onPress(new MouseButtonInfo(0, 0)));
            }
        }));
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            Files.createDirectories(directory);
            switch (stage) {
                case "source" -> createSource(context);
                case "edit" -> editSource(context);
                case "verify" -> verifyUpgrade(context, false, false);
                case "startup" -> verifyUpgrade(context, true, false);
                case "resume" -> verifyUpgrade(context, true, true);
                default -> throw new IllegalArgumentException("Unknown upgrade fixture stage: " + stage);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot run stopped-world upgrade fixture", failed);
        } finally {
            acceptUpgradeDone.set(false);
            freezeSimulation.set(false);
        }
    }

    private void createSource(ClientGameTestContext context) throws IOException {
        LumiClientBehaviorWorld.run(context, "upgrade-source", (test, world, report) -> {
            var ui = new LumiUiTestDriver(test);
            ui.completeOnboardingIfShown();
            ui.awaitHistory();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
                try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                    populate(level);
                }
                fixture.setProperty("world", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().toString());
            });
            var operations = new LumiBehaviorOperations(test, world.getServer(), report);
            fixture.setProperty("head", operations.save("upgrade-source").value().hex());
        });
        copyWorld(Path.of(fixture.getProperty("world")), directory.resolve("source-a"), true);
        writeFixture();
    }

    private void editSource(ClientGameTestContext context) throws IOException {
        readFixture();
        copyWorld(directory.resolve("source-a"), save(context, "lumi-upgrade-target"), true);
        LumiClientBehaviorWorld.runExisting(context, "upgrade-unsaved", "lumi-upgrade-target",
                (test, world, report) -> {
                    new LumiUiTestDriver(test).completeOnboardingIfShown();
                    var operations = new LumiBehaviorOperations(test, world.getServer(), report);
                    fixture.setProperty("head", operations.activeCommit().value().hex());
                    world.getServer().runOnServer(server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
                        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                            player.level().setBlockAndUpdate(CHEST.east(), Blocks.GOLD_BLOCK.defaultBlockState());
                            var chest = (ChestBlockEntity) player.level().getBlockEntity(CHEST);
                            chest.setItem(0, new ItemStack(Items.EMERALD, 19));
                            chest.setChanged();
                        }
                    });
                    operations.awaitDurability("upgrade-unsaved");
                    fixture.setProperty("builderKeys", Integer.toString(operations.pendingBuilderKeyCount()));
                });
        copyWorld(save(context, "lumi-upgrade-target"), directory.resolve("source-b"), true);
        writeFixture();
    }

    private void verifyUpgrade(ClientGameTestContext context, boolean automatic, boolean resume) throws IOException {
        readFixture();
        copyWorld(directory.resolve("source-a"), save(context, "lumi-upgrade-oracle-a"), false);
        copyWorld(directory.resolve("source-b"), save(context, "lumi-upgrade-oracle-b"), false);
        Path targetWorld = save(context, "lumi-upgrade-target");
        copyWorld(directory.resolve(resume ? "crashed-target" : "source-b"), targetWorld, true);
        Path originalHistory = new DimensionRepositoryLayout(targetWorld).resolve("minecraft:overworld");
        var generations = new HistoryGenerationRepository(originalHistory);
        var sourceGenerations = new HistoryGenerationRepository(new DimensionRepositoryLayout(
                directory.resolve("source-b")).resolve("minecraft:overworld"));
        var sourceGeneration = sourceGenerations.active();
        var sourceSnapshot = new HistorySourceSnapshot().capture(sourceGenerations.directory(sourceGeneration));
        var expectedGeneration = resume ? generations.pending().map(pending -> pending.target())
                .orElse(generations.active()) : null;
        LumiClientBehaviorWorld.runExisting(context, "upgrade-vanilla-saved", "lumi-upgrade-oracle-a",
                (test, world, report) -> expectedSaved = world.getServer().computeOnServer(server ->
                        LumiWorldSnapshot.capture(server.getPlayerList().getPlayers().getFirst().level(), AREA)));
        LumiClientBehaviorWorld.runExisting(context, "upgrade-vanilla-unsaved", "lumi-upgrade-oracle-b",
                (test, world, report) -> expectedWorking = world.getServer().computeOnServer(server -> {
                    var level = server.getPlayerList().getPlayers().getFirst().level();
                    converter = new MinecraftWorldPayloadConverter(level.registryAccess(), BuiltInRegistries.ENTITY_TYPE);
                    return LumiWorldSnapshot.capture(level, AREA);
                }));
        if (!automatic) convertBeforeOpening(context);
        LumiClientBehaviorWorld.runExistingWithReopen(context, "upgrade-converted", "lumi-upgrade-target",
                (test, world, report) -> {
                    var ui = new LumiUiTestDriver(test);
                    ui.completeOnboardingIfShown();
                    ui.awaitHistory();
                    var operations = new LumiBehaviorOperations(test, world.getServer(), report);
                    world.getServer().runOnServer(server -> assertWorld(
                            server.getPlayerList().getPlayers().getFirst().level(), expectedWorking, "unsaved upgrade"));
                    if (operations.pendingBuilderKeyCount() != Integer.parseInt(fixture.getProperty("builderKeys"))) {
                        throw new AssertionError("Upgrade changed the unsaved builder boundary");
                    }
                    Path repository = operations.repository();
                    if (resume && !repository.getFileName().toString().equals(expectedGeneration.id().toString())) {
                        throw new AssertionError("Restart abandoned a valid conversion candidate");
                    }
                    var translated = new HistoryConversionMap(repository).read(HistoryConversionMap.Kind.COMMIT,
                            new io.github.lumi.domain.model.ObjectId(fixture.getProperty("head"))).orElseThrow();
                    operations.restore("upgrade-source", new CommitId(translated));
                    world.getServer().runOnServer(server -> assertWorld(
                            server.getPlayerList().getPlayers().getFirst().level(), expectedSaved, "restored upgrade"));
                }, (test, world, report) -> world.getServer().runOnServer(server -> assertWorld(
                        server.getPlayerList().getPlayers().getFirst().level(), expectedSaved, "reopened upgrade")));
        if (!sourceSnapshot.equals(new HistorySourceSnapshot().capture(generations.directory(sourceGeneration)))) {
            throw new AssertionError("Upgrade or Restore changed the original source history");
        }
        if (automatic && !resume) {
            verifyBackup(context);
            copyWorld(targetWorld, directory.resolve("restored-target"), true);
            copyWorld(save(context, "lumi-upgrade-oracle-a"), directory.resolve("vanilla-saved"), false);
            // Crash cuts exercise history conversion after vanilla's independently tested file migration.
            Path nativeFixture = directory.resolve("native-layout");
            copyWorld(save(context, "lumi-upgrade-oracle-a"), nativeFixture.resolve("source-a"), false);
            copyWorld(save(context, "lumi-upgrade-oracle-b"), nativeFixture.resolve("source-b"), false);
            copyDirectory(directory.resolve("source-b/lumi"), nativeFixture.resolve("source-b/lumi"), true);
            Files.copy(directory.resolve("fixture.properties"), nativeFixture.resolve("fixture.properties"));
        }
        if (resume) LumiMod.LOGGER.info("Lumi history upgrade restart {}: succeeded", restartPhase);
    }

    private void convertBeforeOpening(ClientGameTestContext context) throws IOException {
        //? if >=26.1 {
        /*var storage = context.computeOnClient(client -> client.getLevelSource());
        try (var access = storage.createAccess("lumi-upgrade-target")) {
            net.minecraft.util.datafix.DataFixers.getFileFixer().fix(access,
                    access.getUnfixedDataTag(false), new net.minecraft.util.worldupdate.UpgradeProgress());
        }
        *///?}
        var current = new WorldDataVersion(SharedConstants.getCurrentVersion().dataVersion().version());
        try (var dimensions = Files.list(save(context, "lumi-upgrade-target").resolve("lumi/history"))) {
            for (Path dimension : dimensions.toList()) {
                new HistoryGenerationConversionService(dimension).convert(current, converter, progress -> {
                    if (progress.completed() == 0 || progress.completed() % 128 == 0) {
                        LumiMod.LOGGER.info("Upgrade fixture {}: {}", dimension.getFileName(), progress);
                    }
                });
            }
        }
    }

    private void verifyBackup(ClientGameTestContext context) throws IOException {
        Path backups = context.computeOnClient(client -> client.getLevelSource().getBackupPath());
        try (var files = Files.list(backups)) {
            var archives = files.filter(file -> file.getFileName().toString().endsWith(".zip")).toList();
            if (archives.size() != 1) throw new AssertionError("Startup upgrade must create exactly one world backup");
            try (var zip = FileSystems.newFileSystem(archives.getFirst())) {
                Path expected = directory.resolve("source-b/lumi/history");
                Path actual = zip.getPath("/lumi-upgrade-target/lumi/history");
                long expectedCount = 0;
                try (var sourceFiles = Files.walk(expected)) {
                    for (var iterator = sourceFiles.iterator(); iterator.hasNext();) {
                        Path source = iterator.next();
                        if (!Files.isRegularFile(source)) continue;
                        expectedCount++;
                        Path entry = actual.resolve(expected.relativize(source).toString().replace('\\', '/'));
                        if (!Files.isRegularFile(entry) || Files.mismatch(source, entry) != -1) {
                            throw new AssertionError("Upgrade backup changed source history: " + entry);
                        }
                    }
                }
                try (var backupFiles = Files.walk(actual)) {
                    if (expectedCount == 0 || backupFiles.filter(Files::isRegularFile).count() != expectedCount) {
                        throw new AssertionError("Upgrade backup did not preserve the complete source history");
                    }
                }
            }
        }
    }

    private static void populate(ServerLevel level) {
        level.setBlockAndUpdate(CHEST.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
        var diamonds = new ItemStack(Items.DIAMOND, 37);
        diamonds.set(DataComponents.CUSTOM_NAME, Component.literal("Lumi upgrade diamonds"));
        chest.setItem(0, diamonds);
        chest.setChanged();
        level.setBlockAndUpdate(CHEST.east(), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        level.setBlockAndUpdate(CHEST.north(), Blocks.DECORATED_POT.defaultBlockState());
        var root = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        var passenger = EntityType.PIG.create(level, EntitySpawnReason.COMMAND);
        if (root == null || passenger == null) throw new AssertionError("Cannot create upgrade entity tree");
        root.setPos(8.5, 178, 8.5);
        root.setNoGravity(true);
        root.setInvulnerable(true);
        passenger.setNoAi(true);
        passenger.setNoGravity(true);
        passenger.setInvulnerable(true);
        passenger.setPersistenceRequired();
        passenger.setPos(8.5, 179, 8.5);
        if (!passenger.startRiding(root, true, false)) throw new AssertionError("Cannot mount upgrade passenger");
        level.addFreshEntityWithPassengers(root);
        populateBlockEntities(level);
    }

    private static void populateBlockEntities(ServerLevel level) {
        var seen = new java.util.HashSet<net.minecraft.world.level.block.entity.BlockEntityType<?>>();
        seen.add(net.minecraft.world.level.block.entity.BlockEntityType.CHEST);
        seen.add(net.minecraft.world.level.block.entity.BlockEntityType.DECORATED_POT);
        int index = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof net.minecraft.world.level.block.EntityBlock factory)) continue;
            var state = block.defaultBlockState();
            var entity = factory.newBlockEntity(BlockPos.ZERO, state);
            if (entity == null || !seen.add(entity.getType())) continue;
            BlockPos position;
            do {
                if (index >= 128) throw new AssertionError("Upgrade block-entity fixture exceeded its section");
                position = new BlockPos((index % 8) * 2, 177 + (index / 48) * 4, 4 + ((index / 8) % 6) * 2);
                index++;
            } while (position.getX() >= 6 && position.getX() <= 10 && position.getZ() >= 6 && position.getZ() <= 10);
            level.setBlockAndUpdate(position.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(position.above(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(position, state);
            if (block instanceof net.minecraft.world.level.block.BedBlock) {
                var head = position.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
                level.setBlockAndUpdate(head.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(head, state.setValue(BlockStateProperties.BED_PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD));
            }
        }
        var placed = level.getChunk(0, 0).getBlockEntities().values().stream()
                .map(net.minecraft.world.level.block.entity.BlockEntity::getType)
                .collect(java.util.stream.Collectors.toSet());
        if (!placed.containsAll(seen)) throw new AssertionError("Upgrade fixture lost a block-entity type during placement");
        LumiMod.LOGGER.info("Upgrade fixture includes {} block-entity types", seen.size());
    }

    private static void assertWorld(ServerLevel level, LumiWorldSnapshot expected, String label) {
        try {
            LumiWorldSnapshot.capture(level, AREA).assertMatches(expected, label);
        } catch (IOException failed) {
            throw new AssertionError("Cannot capture " + label, failed);
        }
    }

    private void readFixture() throws IOException {
        try (var input = Files.newInputStream(directory.resolve("fixture.properties"))) { fixture.load(input); }
    }

    private static Path save(ClientGameTestContext context, String name) {
        return context.computeOnClient(client -> client.getLevelSource().getBaseDir().resolve(name));
    }

    private static void copyWorld(Path source, Path target, boolean includeHistory) throws IOException {
        if (!Files.isRegularFile(source.resolve("level.dat")) || Files.exists(target)) {
            throw new IOException("Upgrade copy requires a world and an unused destination: " + source + " -> " + target);
        }
        copyDirectory(source, target, includeHistory);
    }

    private static void copyDirectory(Path source, Path target, boolean includeHistory) throws IOException {
        if (!Files.isDirectory(source) || Files.exists(target)) throw new IOException("Invalid upgrade fixture copy: " + target);
        try (var paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path relative = source.relativize(path);
                if (relative.toString().equals("session.lock")
                        || (!includeHistory && relative.startsWith("lumi"))) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link in upgrade fixture: " + path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private void writeFixture() throws IOException {
        try (var output = Files.newOutputStream(directory.resolve("fixture.properties"))) {
            fixture.store(output, "Stopped-world upgrade fixture");
        }
    }
}
