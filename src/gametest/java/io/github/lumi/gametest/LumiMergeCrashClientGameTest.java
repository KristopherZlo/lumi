package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.*;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.storage.object.SectionBlobCodec;
import io.github.lumi.storage.repository.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.fabricmc.loader.api.FabricLoader;

/** Crashes a real divergent, source-wins Merge before publication or during apply. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiMergeCrashClientGameTest implements FabricClientGameTest {
    private static final SectionKey KEY = new SectionKey(0, 0, 0);
    private static final BlockBox AREA = new BlockBox(0, 0, 0, 15, 15, 15);

    @Override public void runTest(ClientGameTestContext context) {
        if (!System.getProperty("lumi.persistence.kind", "").equals("merge")
                || !LumiClientTestSuite.includes(LumiClientTestSuite.PERSISTENCE_CRASH)) return;
        try {
            switch (System.getProperty("lumi.persistence.mode", "")) {
                case "produce" -> produce(context);
                case "verify" -> verify(context);
                default -> throw new IllegalArgumentException("Merge crash mode is missing");
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Merge process-crash gate failed", failed);
        }
    }

    private void produce(ClientGameTestContext context) throws IOException {
        try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(), "merge-crash-produce");
                var world = context.worldBuilder()
                        .adjustSettings(LumiClientBehaviorWorld::configureWorld).create()) {
            prepareClient(context);
            var server = world.getServer();
            var operations = new LumiBehaviorOperations(context, server, report);
            var fixture = new LumiDenseSectionFixture(context, server, report);
            fixture.fill("merge-base", AREA, 0);
            CommitId base = operations.save("merge-base");
            SectionBlob baseSection = captureSection(server);
            BranchName currentName = operations.activeBranch();
            BranchRef source = operations.createBranch("crash-source", base);
            operations.switchBranch("merge-source", source.name());
            fixture.fill("merge-source", AREA, 1);
            CommitId target = operations.save("merge-source");
            SectionBlob sourceSection = captureSection(server);
            operations.switchBranch("merge-current", currentName);
            fixture.fill("merge-current", AREA, 2);
            operations.save("merge-current");
            SectionBlob currentSection = captureSection(server);
            BranchRef before = server.computeOnServer(minecraft -> runtime(minecraft).activeRef());
            Properties metadata = new Properties();
            metadata.setProperty("kind", "merge");
            metadata.setProperty("phase", System.getProperty("lumi.persistence.phase"));
            metadata.setProperty("world", world.getWorldSave().getSaveDirectory().toAbsolutePath().toString());
            metadata.setProperty("before", before.commit().hex());
            metadata.setProperty("branch", before.name().value());
            metadata.setProperty("revision", Long.toString(before.revision()));
            metadata.setProperty("source", target.hex());
            metadata.setProperty("beforeSection", sectionId(server));
            metadata.setProperty("targetSection", expectedMergeSectionId(baseSection, currentSection, sourceSection));
            server.runOnServer(minecraft -> minecraft.saveEverything(false, true, false));
            LumiPersistenceCrashProbe.writeMetadata(metadata);
            LumiPersistenceCrashProbe.arm();
            var preparation = server.computeOnServer(minecraft -> {
                var player = minecraft.getPlayerList().getPlayers().getFirst();
                return runtime(minecraft).prepareMerge(source.name(),
                        new CommitAuthor(player.getUUID(), player.getName().getString()), "crash-merge");
            });
            var plan = awaitFuture(context, preparation);
            require(plan.result().conflicts() > 0, "Merge fixture did not create conflicts");
            var mutation = server.computeOnServer(minecraft -> runtime(minecraft).startMerge(plan, ignored -> { }));
            await(context, () -> server.computeOnServer(minecraft -> mutation.isTerminal()),
                    "Merge never reached its crash cutpoint");
            throw new AssertionError("Merge terminated without its crash cutpoint: "
                    + server.computeOnServer(minecraft -> mutation.terminalState()));
        }
    }

    private void verify(ClientGameTestContext context) throws IOException {
        Properties metadata = LumiPersistenceCrashProbe.readMetadata();
        require(metadata.getProperty("kind").equals("merge")
                && metadata.getProperty("phase").equals(System.getProperty("lumi.persistence.phase")),
                "Merge fixture belongs to another cutpoint");
        var save = new TestWorldSaveImpl(context, Path.of(metadata.getProperty("world")));
        boolean preparationCut = List.of("object-written", "commit-written").contains(metadata.getProperty("phase"));
        CommitId before = commit(metadata.getProperty("before"));
        CommitId expected = preparationCut ? before : commit(metadata.getProperty("candidate"));
        try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(), "merge-crash-verify")) {
            try (var world = save.open()) {
                prepareClient(context);
                var server = world.getServer();
                server.runOnServer(minecraft -> {
                    var runtime = runtime(minecraft);
                    if (runtime.recoveryJournal().isPresent()) {
                        require(runtime.freeze().isFrozen(), "Interrupted Merge is not frozen");
                        runtime.startRecovery(RecoveryChoice.RESUME_TARGET, completed -> {
                            if (completed.terminalState() != io.github.lumi.minecraft.operation.MutationTerminalState.SUCCEEDED) {
                                throw new AssertionError("Merge recovery did not succeed");
                            }
                        });
                    }
                });
                awaitIdle(context, server);
                context.setScreen(() -> null);
                var operations = new LumiBehaviorOperations(context, server, report);
                require(operations.activeCommit().equals(expected), "Merge published an unexpected HEAD");
                require(sectionId(server).equals(metadata.getProperty(preparationCut ? "beforeSection" : "targetSection")),
                        "Recovered Merge violated the source-wins oracle");
                var repository = operations.repository();
                validateTree(repository, before);
                validateTree(repository, commit(metadata.getProperty("source")));
                validateTree(repository, expected);
                if (!preparationCut) {
                    var merged = new CommitRepository(repository).read(expected);
                    require(merged.kind() == CommitKind.MERGE
                            && merged.parents().equals(List.of(before, commit(metadata.getProperty("source")))),
                            "Recovered Merge lost its two-parent history");
                }
                require(new OperationJournalRepository(repository).read().isEmpty(), "Merge left an active journal");
                restore(context, server, before);
                require(sectionId(server).equals(metadata.getProperty("beforeSection")), "Merge destroyed prior current history");
                if (!preparationCut) restore(context, server, expected);
            }
            try (var reopened = save.open()) {
                prepareClient(context);
                awaitIdle(context, reopened.getServer());
                require(new LumiBehaviorOperations(context, reopened.getServer(), report).activeCommit().equals(expected),
                        "Clean reopen changed Merge HEAD");
                require(sectionId(reopened.getServer()).equals(metadata.getProperty(preparationCut ? "beforeSection" : "targetSection")),
                        "Clean reopen changed Merge blocks");
            }
            report.event("gate", "publication_crash_merge_" + metadata.getProperty("phase"), "succeeded", 0, 0,
                    "sourceWins=true;priorHistoryReadable=true;cleanReopen=true");
        }
    }

    private static void validateTree(Path repository, CommitId id) throws IOException {
        var objects = new WorldObjectRepository(repository);
        var graph = new WorldObjectGraph(objects).scan(new CommitRepository(repository).read(id).tree());
        for (ObjectId reachable : graph.reachable()) objects.readCanonical(reachable);
        require(graph.leaves().containsKey(KEY), "Merge history lost the fixture section");
    }
    private static String sectionId(TestServerContext server) throws IOException {
        return ObjectId.hash(new SectionBlobCodec().encode(captureSection(server))).hex();
    }
    private static SectionBlob captureSection(TestServerContext server) throws IOException {
        return server.computeOnServer(minecraft -> new MinecraftSectionCapture().capture(
                minecraft.overworld(), minecraft.overworld().getChunk(0, 0), 0));
    }
    private static String expectedMergeSectionId(SectionBlob base, SectionBlob current, SectionBlob source)
            throws IOException {
        require(base.blockEntities().isEmpty() && current.blockEntities().isEmpty()
                && source.blockEntities().isEmpty(), "Dense Merge oracle requires blocks without block entities");
        var expected = new ArrayList<>(source.blockStates());
        // The source wins changed cells; untouched source cells retain current-only edits.
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            if (source.blockStates().get(index).equals(base.blockStates().get(index))) {
                expected.set(index, current.blockStates().get(index));
            }
        }
        return ObjectId.hash(new SectionBlobCodec().encode(new SectionBlob(expected, Map.of()))).hex();
    }
    private static void prepareClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            com.moulberry.axiom.Axiom.configuration.internal.shownIntroduction = true;
        });
        context.setScreen(() -> null);
        new LumiUiTestDriver(context).awaitHistory();
        new LumiUiTestDriver(context).completeOnboardingIfShown();
    }
    private static void restore(ClientGameTestContext context, TestServerContext server, CommitId target)
            throws IOException {
        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            runtime(minecraft).startRestore(target, new CommitAuthor(player.getUUID(), player.getName().getString()));
        });
        awaitIdle(context, server);
    }
    private static void awaitIdle(ClientGameTestContext context, TestServerContext server) {
        await(context, () -> server.computeOnServer(minecraft -> !runtime(minecraft).freeze().isFrozen()
                && !runtime(minecraft).operations().hasActiveOperation()
                && runtime(minecraft).operations().queuedCount() == 0
                && runtime(minecraft).recoveryJournal().isEmpty()), "Merge recovery did not settle");
    }
    private static <T> T awaitFuture(ClientGameTestContext context, CompletableFuture<T> future) {
        await(context, future::isDone, "Merge preparation did not settle");
        return future.join();
    }
    private static void await(ClientGameTestContext context, BooleanSupplier condition, String failure) {
        for (int tick = 0; tick < 12000; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }
    private static FabricDimensionRuntime runtime(net.minecraft.server.MinecraftServer server) {
        return LumiMod.serverRuntime().find(server.overworld()).orElseThrow();
    }
    private static CommitId commit(String hex) { return new CommitId(new ObjectId(hex)); }
    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
