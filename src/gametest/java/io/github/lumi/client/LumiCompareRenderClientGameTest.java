package io.github.lumi.client;

import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lumi.domain.model.BlockChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;

/** Measures the real overlay pipeline; synthetic Compare input never edits saved history. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiCompareRenderClientGameTest implements FabricClientGameTest {
    private final LumiCompareOverlayRenderer renderer = new LumiCompareOverlayRenderer();
    private final List<String> report = new ArrayList<>(List.of(
            "case,blocks,frame,cpu_ns,gpu_ns"));
    private final List<Pending> pending = new ArrayList<>();
    private List<BlockChange> changes = List.of();
    private String name;
    private boolean xray;
    private int frame;
    private boolean active;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!"COMPARE_RENDER".equalsIgnoreCase(System.getProperty("lumi.gametest.suite"))) return;
        WorldRenderEvents.END_MAIN.register(this::render);
        int previousLimit = context.computeOnClient(client -> client.options.framerateLimit().get());
        try (var world = context.worldBuilder().adjustSettings(settings -> {
            settings.setName("Lumi Compare render benchmark");
            settings.setSeed("710");
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, null);
            settings.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
        }).create()) {
            context.runOnClient(client -> {
                client.options.framerateLimit().set(30);
                client.options.pauseOnLostFocus = false;
            });
            context.setScreen(() -> null);
            context.waitFor(client -> client.player != null && client.level != null);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(50, 105, 135);
                player.setYRot(180);
                player.setXRot(15);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            context.waitTicks(30);
            for (boolean sparse : new boolean[] {false, true}) {
                List<BlockChange> fixture = fixture(sparse);
                for (boolean throughWorld : new boolean[] {false, true}) {
                    context.runOnClient(client -> {
                        changes = fixture;
                        xray = throughWorld;
                        name = (sparse ? "canopy" : "dense") + (xray ? "_xray" : "_normal");
                        frame = 0;
                        active = true;
                    });
                    context.waitFor(client -> frame >= 90 && pending.isEmpty(), 1200);
                    context.takeScreenshot("lumi-compare-" + name);
                    context.runOnClient(client -> active = false);
                }
            }
        } finally {
            context.runOnClient(client -> {
                active = false;
                pending.forEach(sample -> sample.query().close());
                pending.clear();
                client.options.framerateLimit().set(previousLimit);
            });
        }
        try {
            Path output = FabricLoader.getInstance().getGameDir()
                    .resolve("compare-render.csv");
            Files.write(output, report);
        } catch (Exception failed) {
            throw new IllegalStateException("Cannot write Compare render measurements", failed);
        }
    }

    private void render(WorldRenderContext context) {
        if (!active) return;
        pending.removeIf(sample -> {
            var nanos = sample.query().getValue();
            if (nanos.isEmpty()) return false;
            report.add(sample.prefix() + nanos.getAsLong());
            sample.query().close();
            return true;
        });
        if (frame >= 90) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuQuery query = frame >= 30 ? encoder.timerQueryBegin() : null;
        long started = System.nanoTime();
        renderer.render(context, changes, xray);
        // Include the existing renderer's deferred uploads and draws in both measurements.
        if (context.consumers() instanceof MultiBufferSource.BufferSource buffers) {
            buffers.endBatch(LumiCompareRenderTypes.fill(xray));
            buffers.endBatch(LumiCompareRenderTypes.outline(xray));
        }
        long cpuNanos = System.nanoTime() - started;
        if (query != null) {
            encoder.timerQueryEnd(query);
            pending.add(new Pending(query,
                    name + "," + changes.size() + "," + frame + "," + cpuNanos + ","));
        }
        frame++;
    }

    private static List<BlockChange> fixture(boolean sparse) {
        List<BlockChange> blocks = new ArrayList<>();
        int side = sparse ? 100 : 32;
        int height = sparse ? 20 : 32;
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < side; z++) {
                    if (sparse && (x + y + z) % 2 == 0) continue;
                    blocks.add(new BlockChange(x, y + 90, z,
                            BlockChange.Kind.values()[x % 3]));
                }
            }
        }
        return List.copyOf(blocks);
    }

    private record Pending(GpuQuery query, String prefix) { }
}
