package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.*;
import io.github.lumi.domain.service.ImportExportService;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.storage.object.SectionBlobCodec;
import io.github.lumi.storage.packageformat.LumiPackageDirectory;
import io.github.lumi.storage.repository.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.fabricmc.loader.api.FabricLoader;

/** Separate producer/verifier processes for Save and package publication. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiPublicationCrashClientGameTest implements FabricClientGameTest {
    private static final SectionKey KEY = new SectionKey(0, 0, 0);
    private static final BlockBox AREA = new BlockBox(0, 0, 0, 15, 15, 15);
    private static final PackageName PACKAGE = new PackageName("crash-import");

    @Override public void runTest(ClientGameTestContext context) {
        String mode = System.getProperty("lumi.persistence.mode", "");
        if (mode.isEmpty() || !LumiClientTestSuite.includes(LumiClientTestSuite.PERSISTENCE_CRASH)) return;
        try {
            if (mode.equals("produce")) produce(context);
            else if (mode.equals("verify")) verify(context);
            else throw new IllegalArgumentException("Unknown persistence mode: " + mode);
        } catch (IOException failed) {
            throw new IllegalStateException("Publication crash gate failed", failed);
        }
    }

    private void produce(ClientGameTestContext context) throws IOException {
        String kind = kind();
        try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(),
                "publication-crash-" + kind);
                var world = context.worldBuilder().setUseConsistentSettings(false)
                        .adjustSettings(LumiClientBehaviorWorld::configureWorld).create()) {
            prepareClient(context);
            var server = world.getServer();
            var operations = new LumiBehaviorOperations(context, server, report);
            var fixture = new LumiDenseSectionFixture(context, server, report);
            fixture.fill("publication-base", AREA, 0);
            operations.save("publication-base");
            Path repository = operations.repository();
            Properties metadata = new Properties();
            metadata.setProperty("kind", kind);
            metadata.setProperty("phase", System.getProperty("lumi.persistence.phase"));
            metadata.setProperty("world", world.getWorldSave().getSaveDirectory().toAbsolutePath().toString());
            metadata.setProperty("repository", repository.toAbsolutePath().toString());
            BranchRef before = server.computeOnServer(minecraft -> runtime(minecraft).activeRef());
            metadata.setProperty("branch", before.name().value());
            metadata.setProperty("before", before.commit().hex());
            metadata.setProperty("revision", Long.toString(before.revision()));
            metadata.setProperty("baseSection", sectionId(server));
            var author = server.computeOnServer(minecraft -> {
                var player = minecraft.getPlayerList().getPlayers().getFirst();
                return new CommitAuthor(player.getUUID(), player.getName().getString());
            });
            ImportExportService.PackageInspection inspection = null;
            if (kind.equals("save")) {
                fixture.fill("publication-target", AREA, 1);
                metadata.setProperty("targetSection", sectionId(server));
            } else {
                inspection = buildPackage(world.getWorldSave().getSaveDirectory(), before, author, metadata);
            }
            server.runOnServer(minecraft -> minecraft.saveEverything(false, true, false));
            LumiPersistenceCrashProbe.writeMetadata(metadata);
            LumiPersistenceCrashProbe.arm();
            if (kind.equals("save")) {
                context.runOnClient(client -> LumiClient.networking().save("publication-target"));
            } else {
                var confirmed = inspection;
                var future = server.computeOnServer(minecraft -> runtime(minecraft).importPackage(
                        PACKAGE, confirmed, before, author));
                await(context, future::isDone, "Import never reached its cutpoint");
                future.join();
                throw new AssertionError("Import completed without its crash cutpoint");
            }
            context.waitTicks(12000);
            throw new AssertionError("Save never reached its crash cutpoint");
        }
    }

    private ImportExportService.PackageInspection buildPackage(Path world, BranchRef base,
            CommitAuthor author, Properties metadata) throws IOException {
        Path donor = FabricLoader.getInstance().getGameDir().resolve("publication-donor");
        var objects = new WorldObjectRepository(donor);
        var section = SectionBlob.fromPalette(List.of("minecraft:gold_block"),
                new short[SectionBlob.BLOCK_COUNT], Map.of());
        ObjectId id = objects.write(section);
        ObjectId tree = new MerkleTreeEditor(objects).update(Optional.empty(), Map.of(KEY, id));
        CommitId commit = new CommitRepository(donor).write(new Commit(tree, List.of(), author,
                "publication-import", Instant.EPOCH, new UUID(0, 99), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, SectionBlob.BLOCK_COUNT, 0)));
        var packages = new LumiPackageDirectory(world);
        packages.ensureDirectory();
        var inspection = new ImportExportService("minecraft:overworld", donor)
                .export(commit, packages.resolve(PACKAGE), false);
        metadata.setProperty("targetSection", id.hex());
        metadata.setProperty("importBranch", "import/" + PACKAGE.value() + "-" + commit.hex().substring(0, 8));
        return inspection;
    }

    private void verify(ClientGameTestContext context) throws IOException {
        Properties metadata = LumiPersistenceCrashProbe.readMetadata();
        require(kind().equals(metadata.getProperty("kind"))
                && System.getProperty("lumi.persistence.phase").equals(metadata.getProperty("phase")),
                "Crash fixture belongs to another gate");
        var save = new TestWorldSaveImpl(context, Path.of(metadata.getProperty("world")));
        try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(),
                "publication-verify-" + kind())) {
            CommitId expected;
            try (var world = save.open()) {
                prepareClient(context);
                var server = world.getServer();
                awaitIdle(context, server);
                var operations = new LumiBehaviorOperations(context, server, report);
                BranchRef active = server.computeOnServer(minecraft -> runtime(minecraft).activeRef());
                var before = commit(metadata.getProperty("before"));
                Path repository = operations.repository();
                validateTree(repository, before, metadata.getProperty("baseSection"));
                if (kind().equals("save")) {
                    boolean recoverable = List.of("journal-created", "ref-forced", "ref-published",
                            "working-cleared", "journal-complete", "cleanup").contains(metadata.getProperty("phase"));
                    expected = recoverable ? commit(metadata.getProperty("candidate")) : before;
                    require(active.commit().equals(expected), "Save recovery published an unexpected HEAD");
                    require(active.revision() == Long.parseLong(metadata.getProperty("revision"))
                            + (recoverable ? 1 : 0), "Save recovery changed ref revision twice");
                    validateTree(repository, expected, metadata.getProperty(recoverable ? "targetSection" : "baseSection"));
                    operations.restore("prior-history-readable", before);
                    require(sectionId(server).equals(metadata.getProperty("baseSection")), "Prior Save no longer restores");
                    if (recoverable) {
                        operations.restore("published-save-exact", expected);
                        require(sectionId(server).equals(metadata.getProperty("targetSection")), "Published Save restores inexactly");
                    }
                } else {
                    expected = before;
                    require(active.commit().equals(before) && active.revision() == Long.parseLong(metadata.getProperty("revision")),
                            "Import changed the canonical HEAD");
                    require(sectionId(server).equals(metadata.getProperty("baseSection")), "Import changed the live world");
                    var refs = new BranchRefRepository(repository);
                    var branch = refs.read(new BranchName(metadata.getProperty("importBranch")));
                    boolean published = metadata.getProperty("phase").equals("ref-published");
                    require(branch.isPresent() == published, "Import exposed a partial branch");
                    if (published) validateTree(repository, branch.orElseThrow().commit(), metadata.getProperty("targetSection"));
                    else {
                        var runtime = server.computeOnServer(LumiPublicationCrashClientGameTest::runtime);
                        var inspection = awaitFuture(context, runtime.inspectPackage(PACKAGE));
                        var retried = awaitFuture(context, runtime.importPackage(PACKAGE, inspection, active,
                                new CommitAuthor(new UUID(0, 1), "Retry")));
                        validateTree(repository, retried.commit(), metadata.getProperty("targetSection"));
                    }
                }
                awaitIdle(context, server);
                require(new OperationJournalRepository(repository).read().isEmpty(), "Crash recovery left a journal");
            }
            try (var reopened = save.open()) {
                prepareClient(context);
                awaitIdle(context, reopened.getServer());
                require(new LumiBehaviorOperations(context, reopened.getServer(), report).activeCommit().equals(expected),
                        "Clean reopen changed the recovered HEAD");
            }
            report.event("gate", "publication_crash_" + kind() + "_" + metadata.getProperty("phase"),
                    "succeeded", 0, 0, "priorHistoryReadable=true;cleanReopen=true");
        }
    }

    private static void validateTree(Path repository, CommitId id, String section) throws IOException {
        var objects = new WorldObjectRepository(repository);
        var tree = new CommitRepository(repository).read(id).tree();
        var graph = new WorldObjectGraph(objects).scan(tree);
        for (ObjectId reachable : graph.reachable()) objects.readCanonical(reachable);
        require(graph.leaves().get(KEY).hex().equals(section), "Commit section differs from the capture oracle");
    }

    private static String sectionId(TestServerContext server) throws IOException {
        return server.computeOnServer(minecraft -> {
            var level = minecraft.overworld();
            var section = new MinecraftSectionCapture().capture(level, level.getChunk(0, 0), 0);
            return ObjectId.hash(new SectionBlobCodec().encode(section)).hex();
        });
    }

    private static void prepareClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            com.moulberry.axiom.Axiom.configuration.internal.shownIntroduction = true;
        });
        context.setScreen(() -> null);
        var ui = new LumiUiTestDriver(context);
        ui.awaitHistory();
        ui.completeOnboardingIfShown();
    }

    private static void awaitIdle(ClientGameTestContext context, TestServerContext server) {
        await(context, () -> server.computeOnServer(minecraft -> {
            var runtime = runtime(minecraft);
            return !runtime.freeze().isFrozen() && !runtime.operations().hasActiveOperation()
                    && runtime.recoveryJournal().isEmpty();
        }), "Publication recovery did not settle");
    }

    private static <T> T awaitFuture(ClientGameTestContext context, CompletableFuture<T> future) {
        await(context, future::isDone, "Package operation did not complete");
        return future.join();
    }

    private static void await(ClientGameTestContext context, BooleanSupplier condition, String failure) {
        for (int tick = 0; tick < 12000; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }

    private static String kind() {
        String kind = System.getProperty("lumi.persistence.kind");
        if (!List.of("save", "import").contains(kind)) throw new IllegalArgumentException("Unknown publication kind: " + kind);
        return kind;
    }
    private static CommitId commit(String hex) { return new CommitId(new ObjectId(hex)); }
    private static FabricDimensionRuntime runtime(net.minecraft.server.MinecraftServer server) {
        return LumiMod.serverRuntime().find(server.overworld()).orElseThrow();
    }
    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
