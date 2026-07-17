package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;

/** Starts production Lumi operations and records every terminal result and phase. */
final class LumiBehaviorOperations {
    private static final int OPERATION_TIMEOUT_TICKS = 12_000;

    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final CommitAuthor author;

    LumiBehaviorOperations(
            ClientGameTestContext context,
            TestServerContext server,
            LumiBehaviorReport report) {
        this.context = context;
        this.server = server;
        this.report = report;
        author = server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            return new CommitAuthor(player.getUUID(), player.getName().getString());
        });
    }

    CommitId activeCommit() throws IOException {
        return server.computeOnServer(minecraft -> runtime(minecraft).activeRef().commit());
    }

    BranchName activeBranch() throws IOException {
        return server.computeOnServer(minecraft -> runtime(minecraft).activeRef().name());
    }

    CommitId save(String name) throws IOException {
        runOperation("save_" + name, (runtime, terminal) -> runtime.startSave(
                new SaveRequest(
                        runtime.activeRef(), author, name, Instant.now(),
                        runtime.activeWorkspaceId(), Optional.empty(), CommitKind.MANUAL),
                terminal));
        return activeCommit();
    }

    void restore(String name, CommitId target) throws IOException {
        runOperation("restore_" + name,
                (runtime, terminal) -> runtime.startRestore(target, author, terminal));
    }

    void undo(String name) throws IOException {
        runOperation("undo_" + name, (runtime, terminal) -> runtime.startLiveAction(
                author.id(), LiveActionJournal.Direction.UNDO, terminal));
    }

    void redo(String name) throws IOException {
        runOperation("redo_" + name, (runtime, terminal) -> runtime.startLiveAction(
                author.id(), LiveActionJournal.Direction.REDO, terminal));
    }

    void quickRollback() throws IOException {
        runOperation("quick_rollback",
                (runtime, terminal) -> runtime.startQuickRollback(author, terminal));
    }

    void quickRollback(String name) throws IOException {
        runOperation("quick_rollback_" + name,
                (runtime, terminal) -> runtime.startQuickRollback(author, terminal));
    }

    BranchRef createBranch(String name) throws IOException {
        long started = System.nanoTime();
        BranchRef branch = server.computeOnServer(
                minecraft -> runtime(minecraft).createBranch(new BranchName(name)));
        report.event("metadata", "branch_create_" + name, "succeeded", 0,
                elapsedMillis(started), branch.name().value());
        return branch;
    }

    void switchBranch(String name, BranchName target) throws IOException {
        runOperation("branch_switch_" + name,
                (runtime, terminal) -> runtime.startBranchSwitch(target, terminal));
    }

    void merge(String name, BranchName source) throws IOException {
        long started = System.nanoTime();
        CompletableFuture<io.github.lumi.domain.service.PreparedMerge> preparation =
                server.computeOnServer(minecraft ->
                        runtime(minecraft).prepareMerge(source, author, name));
        int ticks = waitForFuture("merge_prepare_" + name, preparation);
        io.github.lumi.domain.service.PreparedMerge plan;
        try {
            plan = preparation.join();
        } catch (CompletionException failed) {
            throw asIOException("Merge preparation failed", failed.getCause());
        }
        report.event("operation", "merge_prepare_" + name, "succeeded", ticks,
                elapsedMillis(started), "conflicts=" + plan.result().conflicts());
        runOperation("merge_" + name,
                (runtime, terminal) -> runtime.startMerge(plan, terminal));
    }

    private void runOperation(String name, OperationStarter starter) throws IOException {
        AtomicReference<DimensionMutation> operation = new AtomicReference<>();
        AtomicReference<DimensionMutation> outcome = new AtomicReference<>();
        long started = System.nanoTime();
        server.runOnServer(minecraft -> operation.set(starter.start(
                runtime(minecraft), outcome::set)));
        int ticks = 0;
        String phase = "";
        while (outcome.get() == null && ticks < OPERATION_TIMEOUT_TICKS) {
            context.waitTick();
            ticks++;
            String currentPhase = server.computeOnServer(
                    minecraft -> operation.get().progress().phase());
            if (!currentPhase.equals(phase)) {
                phase = currentPhase;
                report.event("progress", name, "running", ticks,
                        elapsedMillis(started), currentPhase);
            }
        }
        DimensionMutation terminal = outcome.get();
        if (terminal == null) {
            report.event("operation", name, "timeout", ticks,
                    elapsedMillis(started), phase);
            throw new AssertionError(name + " exceeded "
                    + OPERATION_TIMEOUT_TICKS + " ticks");
        }
        MutationTerminalState state = terminal.terminalState();
        String detail = terminal.failure().map(Throwable::toString).orElse(phase);
        report.event("operation", name, state.name().toLowerCase(), ticks,
                elapsedMillis(started), detail);
        if (state != MutationTerminalState.SUCCEEDED) {
            throw new AssertionError(name + " ended as " + state + ": " + detail);
        }
    }

    private int waitForFuture(String name, CompletableFuture<?> future) {
        int ticks = 0;
        while (!future.isDone() && ticks < OPERATION_TIMEOUT_TICKS) {
            context.waitTick();
            ticks++;
        }
        if (!future.isDone()) {
            throw new AssertionError(name + " exceeded "
                    + OPERATION_TIMEOUT_TICKS + " ticks");
        }
        return ticks;
    }

    private static FabricDimensionRuntime runtime(MinecraftServer server) {
        var level = server.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow(
                () -> new AssertionError("Lumi runtime is not loaded"));
    }

    private static IOException asIOException(String message, Throwable failure) {
        return failure instanceof IOException io
                ? io : new IOException(message, failure);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    private interface OperationStarter {
        DimensionMutation start(
                FabricDimensionRuntime runtime,
                Consumer<DimensionMutation> terminal) throws IOException;
    }
}
