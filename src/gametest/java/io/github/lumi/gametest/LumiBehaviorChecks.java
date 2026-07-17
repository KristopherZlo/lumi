package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Captures exact world state and accumulates independent behavior failures. */
final class LumiBehaviorChecks {
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final LumiBehaviorReport report;
    private final List<String> failures = new ArrayList<>();

    LumiBehaviorChecks(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.report = report;
    }

    LumiWorldSnapshot snapshot(String name, List<BlockBox> areas) throws IOException {
        return singleplayer.getServer().computeOnServer(server -> {
            var level = server.getPlayerList().getPlayers().getFirst().level();
            return LumiWorldSnapshot.capture(level, areas, report, name);
        });
    }

    void assertSnapshot(
            String name,
            List<BlockBox> areas,
            LumiWorldSnapshot expected) throws IOException {
        assertSnapshot(name, snapshot(name, areas), expected);
    }

    void assertSnapshot(
            String name,
            LumiWorldSnapshot actual,
            LumiWorldSnapshot expected) {
        try {
            actual.assertMatches(expected, name);
            report.event("assertion", name, "succeeded", 0, 0, "");
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
            report.event("assertion", name, "failed", 0, 0,
                    mismatch.getMessage());
        }
    }

    void waitTicks(String name, int ticks) {
        long started = System.nanoTime();
        context.waitTicks(ticks);
        report.event("wait", name, "completed", ticks,
                elapsedMillis(started), "");
    }

    void waitUntil(String name, int maximumTicks, BooleanSupplier condition) {
        long started = System.nanoTime();
        int ticks = 0;
        while (!condition.getAsBoolean() && ticks < maximumTicks) {
            context.waitTick();
            ticks++;
        }
        String status = condition.getAsBoolean() ? "completed" : "timeout";
        report.event("wait", name, status, ticks, elapsedMillis(started), "");
        if (!status.equals("completed")) {
            throw new AssertionError(name + " exceeded " + maximumTicks + " ticks");
        }
    }

    void screenshot(String name) {
        long started = System.nanoTime();
        var path = context.takeScreenshot("lumi-behavior-" + name);
        report.event("screenshot", name, "captured", 0,
                elapsedMillis(started), path.toString());
    }

    void finish() {
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size()
                    + " exact snapshot checks failed: " + String.join(" | ", failures));
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
