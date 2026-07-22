package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.SaveOperationStatus;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;

/** Drives Lumi's player UI and records the resulting server outcomes. */
final class LumiBehaviorOperations {
    private static final int OPERATION_TIMEOUT_TICKS = Integer.getInteger(
            "lumi.gametest.operationTimeoutTicks", 12_000);

    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final LumiUiTestDriver ui;

    LumiBehaviorOperations(
            ClientGameTestContext context,
            TestServerContext server,
            LumiBehaviorReport report) {
        this.context = Objects.requireNonNull(context, "context");
        this.server = Objects.requireNonNull(server, "server");
        this.report = Objects.requireNonNull(report, "report");
        this.ui = new LumiUiTestDriver(context);
    }

    CommitId activeCommit() throws IOException {
        return server.computeOnServer(minecraft -> runtime(minecraft).activeRef().commit());
    }

    BranchName activeBranch() throws IOException {
        return server.computeOnServer(minecraft -> runtime(minecraft).activeRef().name());
    }

    Path repository() {
        return server.computeOnServer(minecraft -> runtime(minecraft).repository());
    }

    void awaitDurability(String name) {
        var boundary = server.computeOnServer(minecraft ->
                runtime(minecraft).mutations().durabilityBoundary());
        int keys = boundary.working().generations().size();
        long started = System.nanoTime();
        for (int ticks = 0; ticks < OPERATION_TIMEOUT_TICKS; ticks++) {
            boolean durable = server.computeOnServer(minecraft ->
                    runtime(minecraft).mutations().isDurable(boundary));
            if (durable) {
                context.waitTick();
                report.event("durability", name, "succeeded", ticks,
                        elapsedMillis(started), "keys=" + keys);
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(name + " did not become durable within "
                + OPERATION_TIMEOUT_TICKS + " ticks; keys=" + keys);
    }

    CommitId save(String name) throws IOException {
        return runOperation("save_" + name, () -> ui.save(name)).head();
    }

    SavedBoundary save(String name, List<BlockBox> areas) throws IOException {
        awaitHistoryReady();
        AtomicReference<LumiWorldSnapshot> before = new AtomicReference<>();
        AtomicReference<LumiWorldSnapshot> snapshot = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.computeOnServer(minecraft -> {
            FabricDimensionRuntime runtime = runtime(minecraft);
            runtime.operations().observeNextEnqueue((ticket, accepted) -> {
                if (!(accepted instanceof SaveCaptureOperation save)) {
                    failure.compareAndSet(null, new AssertionError(
                            "UI Save did not enqueue SaveCaptureOperation: "
                                    + accepted.getClass().getName()));
                    return;
                }
                runtime.operations().observeFreezeAcquired(ticket,
                        () -> captureFrozen(runtime, areas,
                                "save_" + name + "_before_frozen", before, failure));
                runtime.operations().observeBeforeFreezeRelease(ticket, () -> {
                    if (save.status() != SaveOperationStatus.WRITING) {
                        failure.compareAndSet(null, new AssertionError(
                                "UI Save released its freeze in " + save.status()));
                        return;
                    }
                    captureFrozen(runtime, areas, "save_" + name + "_frozen",
                            snapshot, failure);
                });
            });
            return null;
        });
        long started = System.nanoTime();
        UUID requestId = send("save_" + name, () -> ui.save(name), started);
        OperationEventPayload event = awaitOperation(
                "save_" + name, requestId, failure, started);
        requireCapture("save_" + name + " before", before, failure);
        requireCapture("save_" + name, snapshot, failure);
        String boundaryName = "save_" + name + "_frozen_stable";
        try {
            snapshot.get().assertMatches(before.get(), boundaryName);
            report.event("assertion", boundaryName, "succeeded", 0, 0, "");
        } catch (AssertionError mismatch) {
            report.event("assertion", boundaryName, "failed", 0, 0,
                    mismatch.getMessage());
            throw mismatch;
        }
        return new SavedBoundary(event.head(), snapshot.get());
    }

    void restore(String name, CommitId target) throws IOException {
        runOperation("restore_" + name, () -> restoreFromUi(target));
    }

    LumiRestoreMeasurement measureRestore(String name, CommitId target)
            throws IOException {
        awaitHistoryReady();
        AtomicReference<RestoreApplyStatistics> statistics = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.computeOnServer(minecraft -> {
            FabricDimensionRuntime runtime = runtime(minecraft);
            runtime.operations().observeNextEnqueue((ticket, ignored) ->
                    runtime.operations().observeTerminal(ticket, completed -> {
                        Optional<RestoreApplyStatistics> measured =
                                completed.restoreStatistics();
                        if (measured.isEmpty()) {
                            failure.compareAndSet(null, new AssertionError(
                                    "Restore completed without apply statistics"));
                        } else {
                            statistics.set(measured.orElseThrow());
                        }
                    }));
            return null;
        });
        Runtime jvm = Runtime.getRuntime();
        long heapBefore = usedHeap(jvm);
        long[] peakHeap = {heapBefore};
        long started = System.nanoTime();
        long maximumServerTick;
        try (LumiServerTickProbe ticks = LumiServerTickProbe.open()) {
            awaitOperation("restore_" + name,
                    send("restore_" + name, () -> restoreFromUi(target), started),
                    failure, started,
                    () -> peakHeap[0] = Math.max(peakHeap[0], usedHeap(jvm)));
            maximumServerTick = ticks.maximumNanos();
        }
        RestoreApplyStatistics measured = statistics.get();
        if (measured == null) {
            throw new AssertionError("Restore metrics observer did not complete");
        }
        return new LumiRestoreMeasurement(
                elapsedMillis(started), heapBefore, peakHeap[0],
                maximumServerTick, measured);
    }

    OperationBoundary restore(
            String name, CommitId target, List<BlockBox> areas) throws IOException {
        return runOperation("restore_" + name, areas,
                () -> restoreFromUi(target));
    }

    void undo(String name) throws IOException {
        runOperation("undo_" + name, () -> ui.pressChord("key.lumi.undo"));
    }

    OperationBoundary undo(String name, List<BlockBox> areas) throws IOException {
        return runOperation(
                "undo_" + name, areas, () -> ui.pressChord("key.lumi.undo"));
    }

    void redo(String name) throws IOException {
        runOperation("redo_" + name, () -> ui.pressChord("key.lumi.redo"));
    }

    OperationBoundary redo(String name, List<BlockBox> areas) throws IOException {
        return runOperation(
                "redo_" + name, areas, () -> ui.pressChord("key.lumi.redo"));
    }

    void quickRollback() throws IOException {
        runOperation("quick_rollback", () ->
                ui.pressStandalone("key.lumi.quick_rollback"));
    }

    void quickRollback(String name) throws IOException {
        runOperation(
                "quick_rollback_" + name,
                () -> ui.pressStandalone("key.lumi.quick_rollback"));
    }

    OperationBoundary quickRollback(String name, List<BlockBox> areas)
            throws IOException {
        return runOperation(
                "quick_rollback_" + name, areas,
                () -> ui.pressStandalone("key.lumi.quick_rollback"));
    }

    BranchRef createBranch(String name) throws IOException {
        runOperation("branch_create_" + name, () -> ui.createBranch(name));
        return server.computeOnServer(minecraft -> {
            FabricDimensionRuntime runtime = runtime(minecraft);
            BranchName storedName = runtime.visibleBranchName(new BranchName(name));
            return runtime.visibleBranches().stream()
                    .filter(branch -> branch.name().equals(storedName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Created branch is absent from server history: " + storedName));
        });
    }

    void switchBranch(String name, BranchName target) throws IOException {
        runOperation("branch_switch_" + name, () -> switchBranchFromHotkey(target));
        awaitSnapshot(snapshot -> snapshot.branchName().equals(target.value()),
                "active branch " + target.value());
    }

    OperationBoundary switchBranch(
            String name, BranchName target, List<BlockBox> areas) throws IOException {
        OperationBoundary boundary = runOperation(
                "branch_switch_" + name, areas,
                () -> switchBranchFromHotkey(target));
        awaitSnapshot(snapshot -> snapshot.branchName().equals(target.value()),
                "active branch " + target.value());
        return boundary;
    }

    void merge(String name, BranchName source) throws IOException {
        runOperation("merge_" + name, () -> mergeFromUi(source));
    }

    private OperationEventPayload runOperation(
            String name, ClientAction action) throws IOException {
        awaitHistoryReady();
        long started = System.nanoTime();
        return awaitOperation(
                name, send(name, action, started), new AtomicReference<>(), started);
    }

    private OperationBoundary runOperation(
            String name, List<BlockBox> areas, ClientAction action) throws IOException {
        awaitHistoryReady();
        AtomicReference<LumiWorldSnapshot> before = new AtomicReference<>();
        AtomicReference<LumiWorldSnapshot> after = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.computeOnServer(minecraft -> {
            FabricDimensionRuntime runtime = runtime(minecraft);
            runtime.operations().observeNextEnqueue((ticket, ignored) -> {
                runtime.operations().observeFreezeAcquired(ticket,
                        () -> captureFrozen(runtime, areas,
                                name + "_before_frozen", before, failure));
                runtime.operations().observeTerminal(ticket,
                        terminal -> captureFrozen(runtime, areas,
                                name + "_after_frozen", after, failure));
            });
            return null;
        });
        long started = System.nanoTime();
        OperationEventPayload event = awaitOperation(
                name, send(name, action, started), failure, started);
        requireCapture(name + " before", before, failure);
        requireCapture(name + " after", after, failure);
        Optional<String> completion = "Operation completed".equals(event.message())
                ? Optional.empty() : Optional.of(event.message());
        return new OperationBoundary(before.get(), after.get(), completion);
    }

    private UUID send(String name, ClientAction action, long started) {
        Set<UUID> known = context.computeOnClient(client ->
                LumiClient.history().state().events().keySet());
        action.run();
        int ticks = 0;
        while (ticks < OPERATION_TIMEOUT_TICKS) {
            Set<UUID> added = context.computeOnClient(client -> {
                Set<UUID> ids = new HashSet<>(
                        LumiClient.history().state().events().keySet());
                ids.removeAll(known);
                return ids;
            });
            if (added.size() == 1) {
                UUID requestId = added.iterator().next();
                report.event("ui_request", name, "accepted", ticks,
                        elapsedMillis(started), requestId.toString());
                return requestId;
            }
            if (added.size() > 1) {
                throw new AssertionError(name + " produced multiple UI requests: " + added);
            }
            context.waitTick();
            ticks++;
        }
        report.event("ui_request", name, "timeout", ticks,
                elapsedMillis(started), "No new operation event");
        throw new AssertionError(name + " produced no operation event within "
                + OPERATION_TIMEOUT_TICKS + " ticks");
    }

    private void restoreFromUi(CommitId target) {
        ui.restore(target);
    }

    private void switchBranchFromHotkey(BranchName target) {
        awaitSnapshot(snapshot -> snapshot.branches().stream().anyMatch(branch ->
                        branch.name().equals(target.value()) && !branch.active()),
                "inactive branch " + target.value());
        int slot = context.computeOnClient(client -> {
            List<HistorySnapshotPayload.Branch> branches = LumiClient.history()
                    .state().snapshot().orElseThrow().branches();
            for (int index = 0; index < branches.size(); index++) {
                if (branches.get(index).name().equals(target.value())) {
                    return index;
                }
            }
            return -1;
        });
        if (slot < 0 || slot > 9) {
            throw new AssertionError("Branch has no Alt+digit slot: " + target.value());
        }
        ui.pressChord("key.lumi.branch_slot." + (slot == 9 ? 0 : slot + 1));
    }

    private void mergeFromUi(BranchName source) {
        awaitSnapshot(snapshot -> snapshot.branches().stream().anyMatch(branch ->
                        branch.name().equals(source.value()) && !branch.active()),
                "merge source " + source.value());
        int sourceButton = context.computeOnClient(client -> {
            List<HistorySnapshotPayload.Branch> branches = LumiClient.history()
                    .state().snapshot().orElseThrow().branches();
            int inactive = 0;
            for (HistorySnapshotPayload.Branch branch : branches) {
                if (branch.active()) {
                    continue;
                }
                if (branch.name().equals(source.value())) {
                    return inactive;
                }
                inactive++;
            }
            return -1;
        });
        if (sourceButton < 0 || sourceButton >= 6) {
            throw new AssertionError("Merge source is outside the visible UI page: "
                    + source.value());
        }

        ui.merge(sourceButton);
    }

    private void awaitSnapshot(
            Predicate<HistorySnapshotPayload> predicate, String expected) {
        int ticks = 0;
        while (ticks < OPERATION_TIMEOUT_TICKS) {
            boolean matches = context.computeOnClient(client -> LumiClient.history()
                    .state().snapshot().filter(predicate).isPresent());
            if (matches) {
                return;
            }
            context.waitTick();
            ticks++;
        }
        throw new AssertionError("Lumi client snapshot did not expose " + expected
                + " within " + OPERATION_TIMEOUT_TICKS + " ticks");
    }

    private OperationEventPayload awaitOperation(
            String name,
            UUID requestId,
            AtomicReference<Throwable> failure,
            long started) throws IOException {
        return awaitOperation(name, requestId, failure, started, () -> { });
    }

    private OperationEventPayload awaitOperation(
            String name,
            UUID requestId,
            AtomicReference<Throwable> failure,
            long started,
            Runnable sample) throws IOException {
        int ticks = 0;
        String phase = "";
        long phaseStarted = started;
        OperationEventPayload terminal = null;
        while (terminal == null && ticks < OPERATION_TIMEOUT_TICKS) {
            sample.run();
            OperationEventPayload event = context.computeOnClient(client ->
                    LumiClient.history().state().events().get(requestId));
            if (event != null && event.state() == OperationEventPayload.State.PROGRESS) {
                String currentPhase = event.progress().orElseThrow().phase();
                if (!currentPhase.equals(phase)) {
                    if (!phase.isBlank()) {
                        report.event("phase", name + ":" + phase, "completed",
                                ticks, elapsedMillis(phaseStarted), "");
                    }
                    phase = currentPhase;
                    phaseStarted = System.nanoTime();
                    report.event("progress", name, "running", ticks,
                            elapsedMillis(started), currentPhase);
                }
            } else if (event != null && isTerminal(event.state())) {
                terminal = event;
                break;
            }
            context.waitTick();
            ticks++;
        }
        if (terminal == null) {
            report.event("operation", name, "timeout", ticks,
                    elapsedMillis(started), phase);
            throw new AssertionError(name + " exceeded "
                    + OPERATION_TIMEOUT_TICKS + " ticks");
        }
        if (!phase.isBlank()) {
            report.event("phase", name + ":" + phase, "completed",
                    ticks, elapsedMillis(phaseStarted), "");
        }
        report.event("operation", name, terminal.state().name().toLowerCase(), ticks,
                elapsedMillis(started), terminal.message());
        if (terminal.state() != OperationEventPayload.State.SUCCEEDED) {
            throw new AssertionError(name + " ended as "
                    + terminal.state() + ": " + terminal.message());
        }
        throwFailure(name, failure.get());
        return terminal;
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void awaitHistoryReady() {
        int ticks = 0;
        while (ticks < OPERATION_TIMEOUT_TICKS) {
            boolean ready = context.computeOnClient(client ->
                    LumiClient.history().state().snapshot().isPresent());
            if (ready) {
                return;
            }
            context.waitTick();
            ticks++;
        }
        throw new AssertionError("Lumi client history did not synchronize within "
                + OPERATION_TIMEOUT_TICKS + " ticks");
    }

    private static boolean isTerminal(OperationEventPayload.State state) {
        return state != OperationEventPayload.State.ACCEPTED
                && state != OperationEventPayload.State.PROGRESS;
    }

    private void captureFrozen(
            FabricDimensionRuntime runtime,
            List<BlockBox> areas,
            String name,
            AtomicReference<LumiWorldSnapshot> target,
            AtomicReference<Throwable> failure) {
        try {
            if (!runtime.freeze().isFrozen()) {
                throw new AssertionError(name + " was captured outside the dimension freeze");
            }
            target.compareAndSet(null,
                    LumiWorldSnapshot.capture(runtime.level(), areas, report, name));
        } catch (Throwable failed) {
            failure.compareAndSet(null, failed);
        }
    }

    private static void requireCapture(
            String name,
            AtomicReference<LumiWorldSnapshot> snapshot,
            AtomicReference<Throwable> failure) throws IOException {
        throwFailure(name, failure.get());
        if (snapshot.get() == null) {
            throw new AssertionError(name + " snapshot was not captured");
        }
    }

    private static void throwFailure(String name, Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException(name + " failed", failure);
    }

    private static FabricDimensionRuntime runtime(MinecraftServer server) {
        var level = server.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow(
                () -> new AssertionError("Lumi runtime is not loaded"));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record OperationBoundary(
            LumiWorldSnapshot before,
            LumiWorldSnapshot after,
            Optional<String> completionMessage) {
        OperationBoundary {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(completionMessage, "completionMessage");
        }
    }

    record SavedBoundary(CommitId commit, LumiWorldSnapshot snapshot) {
        SavedBoundary {
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    @FunctionalInterface
    private interface ClientAction { void run(); }
}
