package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

/** Drives ordinary client requests with the existing exact oracle and measurement probe. */
public final class LumiOrdinaryRestoreSoak {
    private record Prepared(LumiRestoreMeasurementProbe probe, LumiServerTickProbe ticks) {}
    private final LumiColdRestoreManifest manifest;
    private final LumiRestoreSoakChecks checks;
    private final int steps;
    private final List<LumiRestoreMeasurement> measurements = new ArrayList<>();
    private ServerLevel level;
    private CompletableFuture<BranchRef> verification;
    private CompletableFuture<Prepared> preparation;
    private Prepared prepared;
    private UUID request;
    private int step = -1;
    private long deadline;
    private boolean ready;

    public LumiOrdinaryRestoreSoak(Path path, int cycles) throws IOException {
        if (cycles < 0 || cycles > 1000) throw new IllegalArgumentException("Invalid ordinary Restore cycles");
        manifest = LumiColdRestoreManifest.read(path);
        checks = new LumiRestoreSoakChecks(manifest);
        steps = cycles * 2;
    }

    public boolean tick(Minecraft client) {
        if (ready) return true;
        if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return false;
        if (deadline == 0) deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        if (System.nanoTime() > deadline) throw new IllegalStateException("Ordinary Restore timed out at step " + step);
        var snapshot = LumiClient.history().state().snapshot();
        if (snapshot.isEmpty() || client.screen != null) return false;
        if (level == null) {
            level = client.getSingleplayerServer().getLevel(client.level.dimension());
            verification = verify(false);
        }
        if (verification != null) {
            if (!verification.isDone()) return false;
            var ref = verification.join();
            if (!snapshot.get().head().equals(ref.commit()) || snapshot.get().revision() != ref.revision()) return false;
            verification = null;
            if (++step == steps) {
                if (steps > 0) {
                    var area = manifest.area();
                    var config = new LumiHistoryBenchmarkConfig(area.maxX() - area.minX() + 1,
                            area.maxX() - area.minX() + 1, area.maxY() - area.minY() + 1, 1, 2, 1, 710,
                            LumiHistoryBenchmarkConfig.ChunkPath.NATURAL, LumiHistoryBenchmarkConfig.OperationMode.RESTORE,
                            LumiHistoryBenchmarkConfig.Profile.NATIVE, false);
                    System.out.println("Ordinary Restore performance: " + LumiRestorePerformanceGate.verify(config, measurements).describe());
                }
                ready = true;
                return true;
            }
        }
        if (request != null) {
            prepared.probe().sampleHeap();
            var event = LumiClient.history().state().events().get(request);
            if (event == null || event.state() == OperationEventPayload.State.ACCEPTED
                    || event.state() == OperationEventPayload.State.PROGRESS) return false;
            if (event.state() != OperationEventPayload.State.SUCCEEDED) {
                prepared.probe().close();
                throw new AssertionError("Ordinary Restore failed: " + event);
            }
            if (!prepared.probe().coversTerminalTick()) return false;
            prepared.probe().close();
            var measured = prepared.probe().measurement();
            measurements.add(measured);
            System.out.println("Ordinary Restore step " + step + ": " + measured.describe());
            request = null;
            prepared = null;
            verification = verify(step % 2 == 0);
            return false;
        }
        if (preparation == null) {
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            preparation = CompletableFuture.supplyAsync(() -> new Prepared(
                    new LumiRestoreMeasurementProbe(LumiMod.serverRuntime().find(level).orElseThrow()),
                    LumiServerTickProbe.open(level.getServer())), level.getServer()::execute);
        }
        if (preparation.isDone()) {
            prepared = preparation.join();
            preparation = null;
            prepared.probe().begin(prepared.ticks());
            request = LumiClient.networking().restore(checks.target(step % 2 == 0));
        }
        return false;
    }

    public void verifyCompleted() {
        if (!ready) throw new IllegalStateException("Ordinary Restore cycles did not complete");
        verify(false).orTimeout(2, TimeUnit.MINUTES).join();
    }

    private CompletableFuture<BranchRef> verify(boolean initial) {
        return CompletableFuture.supplyAsync(() -> {
            try { return checks.verify(level, initial); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
        }, level.getServer()::execute);
    }
}
