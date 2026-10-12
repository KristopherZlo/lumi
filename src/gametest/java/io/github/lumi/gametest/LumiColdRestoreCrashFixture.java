package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.world.ChunkLoadGate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.level.ChunkPos;

/** Adds a distant exact endpoint to the real-process Restore crash matrix. */
final class LumiColdRestoreCrashFixture {
    private static final BlockBox AREA = new BlockBox(1792, 192, 1792, 1919, 207, 1855);
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiDenseSectionFixture dense;

    LumiColdRestoreCrashFixture(ClientGameTestContext context, TestServerContext server,
            LumiBehaviorReport report) {
        this.context = context;
        this.server = server;
        dense = new LumiDenseSectionFixture(context, server, report);
    }

    String buildTarget() {
        dense.fill("crash_cold_target", AREA, 0);
        return capture(server);
    }

    void buildReturn() {
        dense.fill("crash_cold_return", AREA, 1);
    }

    void prewarm(CommitId target) throws IOException {
        dense.awaitUnloaded("crash_stored_chunks_ready", AREA);
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
            try {
                runtime.prewarmRestore(runtime.activeRef(), target);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        });
        boolean ready = false;
        for (int tick = 0; tick < 1_500; tick++) {
            ready = server.computeOnServer(minecraft -> {
                var level = minecraft.getPlayerList().getPlayers().getFirst().level();
                var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
                return runtime.isRestorePrewarmed(runtime.activeRef(), target);
            });
            if (ready) break;
            context.waitTick();
        }
        if (!ready) throw new AssertionError("Cold crash Restore prewarm did not finish");
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            for (int z = AREA.minZ() / 16; z <= AREA.maxZ() / 16; z++) {
                for (int x = AREA.minX() / 16; x <= AREA.maxX() / 16; x++) {
                    long key = ChunkPos.asLong(x, z);
                    if (level.getChunkSource().getChunkNow(x, z) != null
                            || level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key) != null
                            || ((ChunkLoadGate.PendingUnloadAccess) level.getChunkSource().chunkMap)
                                    .lumi$hasPendingUnload(key)) {
                        throw new AssertionError("Cold crash prewarm loaded fixture chunk " + x + "," + z);
                    }
                }
            }
        });
    }

    static void assertTarget(TestServerContext server, String expected) {
        String actual = capture(server);
        if (!expected.equals(actual)) {
            throw new AssertionError("Recovered cold endpoint mismatch: expected="
                    + expected + ";actual=" + actual);
        }
    }

    private static String capture(TestServerContext server) {
        return server.computeOnServer(minecraft -> {
            try {
                return LumiWorldSnapshot.capture(
                        minecraft.getPlayerList().getPlayers().getFirst().level(),
                        List.of(AREA)).sha256();
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        });
    }
}
