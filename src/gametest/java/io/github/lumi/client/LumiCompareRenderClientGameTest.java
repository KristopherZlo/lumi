package io.github.lumi.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lumi.domain.model.BlockChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;
//? if <26.3
import org.lwjgl.glfw.GLFW;

/** Measures the real overlay pipeline; synthetic Compare input never edits saved history. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiCompareRenderClientGameTest implements FabricClientGameTest {
    private LumiCompareOverlayRenderer renderer = new LumiCompareOverlayRenderer();
    private final List<String> report = new ArrayList<>(List.of(
            "case,blocks,frame,cpu_ns,gpu_ns"));
    private final List<Pending> pending = new ArrayList<>();
    private final List<String> preparationReport = new ArrayList<>(List.of("case,ready_ns,resident_bytes"));
    private List<BlockChange> changes = List.of();
    private String name;
    private boolean xray;
    private int frame;
    private boolean active;
    private long inputStarted;
    private boolean observedReady;
    private FogRenderer benchmarkFog;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!"COMPARE_RENDER".equalsIgnoreCase(System.getProperty("lumi.gametest.suite"))) return;
        initialize(context);
        int previousLimit = context.computeOnClient(client -> client.options.framerateLimit().get());
        Path denseXrayScreenshot = null;
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
            assertDepthOcclusion(context, world);
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
                        client.player.setYRot(180);
                        client.player.yRotO = 180;
                        client.player.setXRot(8);
                        client.player.xRotO = 8;
                        changes = fixture;
                        xray = throughWorld;
                        name = (sparse ? "canopy" : "dense") + (xray ? "_xray" : "_normal");
                        frame = 0;
                        inputStarted = System.nanoTime();
                        observedReady = false;
                        active = true;
                    });
                    context.waitFor(client -> frame >= 90 && pending.isEmpty(), 1200);
                    Path screenshot = takeHighlightScreenshot(context, "lumi-compare-" + name);
                    assertHighlight(screenshot);
                    if (!sparse && throughWorld) {
                        denseXrayScreenshot = screenshot;
                        long cachedBytes = context.computeOnClient(client -> renderer.cachedBytes());
                        context.runOnClient(client -> {
                            client.player.setYRot(0);
                            client.player.yRotO = 0;
                        });
                        context.waitTicks(3);
                        context.runOnClient(client -> {
                            client.player.setYRot(180);
                            client.player.yRotO = 180;
                        });
                        context.waitTicks(3);
                        context.runOnClient(client -> {
                            if (!renderer.isReady() || renderer.cachedBytes() != cachedBytes) {
                                throw new AssertionError("Camera rotation discarded retained Compare geometry");
                            }
                        });
                        assertSameHighlight(screenshot, takeHighlightScreenshot(context, "lumi-compare-camera-return"));
                    }
                    context.runOnClient(client -> active = false);
                }
            }
            new LumiCompareToggleScenario().run(context, fixture(true));
            context.runOnClient(client -> {
                changes = List.of();
                active = true;
            });
            context.waitFor(client -> renderer.cachedBytes() == 0);
            context.runOnClient(client -> {
                active = false;
                renderer.close();
                if (renderer.cachedBytes() != 0) throw new AssertionError("Hidden Compare leaked GPU vertices");
                renderer = new LumiCompareOverlayRenderer(0);
                changes = fixture(false);
                name = "dense_zero_resident_budget";
                xray = true;
                frame = 0;
                inputStarted = System.nanoTime();
                observedReady = false;
                active = true;
            });
            context.waitFor(client -> frame >= 90 && pending.isEmpty(), 1200);
            Path transientScreenshot = takeHighlightScreenshot(context, "lumi-compare-zero-budget");
            assertHighlight(transientScreenshot);
            assertSameHighlight(denseXrayScreenshot, transientScreenshot);
            context.runOnClient(client -> {
                active = false;
                if (renderer.cachedBytes() != 0) throw new AssertionError("Compare exceeded its zero-byte cache budget");
            });
        } finally {
            close(context);
            context.runOnClient(client -> client.options.framerateLimit().set(previousLimit));
        }
        try {
            Path output = FabricLoader.getInstance().getGameDir()
                    .resolve("compare-render.csv");
            Files.write(output, report);
            Files.write(output.resolveSibling("compare-render-preparation.csv"), preparationReport);
            String environment = context.computeOnClient(client ->
                    "GPU=" + RenderSystem.getDevice().getRenderer()
                    + "\nJDK=" + System.getProperty("java.version")
                    + "\nframebuffer=" + client.getWindow().getWidth() + "x" + client.getWindow().getHeight()
                    //? if >=26.3 {
                    /*+ "\nwindow_visible=" + ((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                            & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN) == 0)
                    *///?} else {
                    + "\nwindow_visible=" + GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_VISIBLE)
                    //?}
                    + "\nframe_limit=30\noverlay_fog=NONE\n");
            Files.writeString(output.resolveSibling("compare-render-environment.txt"), environment);
        } catch (Exception failed) {
            throw new IllegalStateException("Cannot write Compare render measurements", failed);
        }
    }

    /** Runs only the existing controlled-wall regression in the caller's world. */
    public void verifyDepth(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        initialize(context);
        try {
            assertDepthOcclusion(context, world);
        } finally {
            close(context);
        }
    }

    private void initialize(ClientGameTestContext context) {
        WorldRenderEvents.END_MAIN.register(this::render);
        //? if >=26.1 {
        /*WorldRenderEvents.END_EXTRACTION.register(extracted ->
                renderer.setFrustum(extracted.levelState().cameraRenderState.cullFrustum));
        *///?} else {
        WorldRenderEvents.END_EXTRACTION.register(extracted -> renderer.setFrustum(extracted.frustum()));
        //?}
        context.runOnClient(client -> benchmarkFog = new FogRenderer());
    }

    private void close(ClientGameTestContext context) {
        context.runOnClient(client -> {
            active = false;
            pending.forEach(sample -> sample.query().close());
            pending.clear();
            benchmarkFog.close();
            renderer.close();
        });
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
        LumiGpuTimer query = frame >= 30 && frame < 90 ? new LumiGpuTimer() : null;
        long started = System.nanoTime();
        var previousFog = RenderSystem.getShaderFog();
        RenderSystem.setShaderFog(benchmarkFog.getBuffer(FogRenderer.FogMode.NONE));
        renderer.render(context, changes, xray);
        if (renderer.cachedBytes() > 128L * 1024 * 1024) {
            throw new AssertionError("Compare exceeded its resident GPU budget");
        }
        if (!renderer.isReady()) frame = 0;
        if (renderer.isReady() && !observedReady) {
            preparationReport.add(name + "," + (System.nanoTime() - inputStarted)
                    + "," + renderer.cachedBytes());
            observedReady = true;
        }
        if (previousFog != null) RenderSystem.setShaderFog(previousFog);
        long cpuNanos = System.nanoTime() - started;
        if (query != null) {
            query.finish();
            pending.add(new Pending(query,
                    name + "," + changes.size() + "," + frame + "," + cpuNanos + ","));
        }
        frame++;
    }

    private void assertDepthOcclusion(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(8.5, 178.88, 16.5);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        context.waitTicks(30);
        for (int stage = 0; stage < 3; stage++) {
            if (stage == 1) {
                world.getServer().runOnServer(server -> {
                    var level = server.overworld();
                    for (int x = 5; x <= 11; x++) {
                        for (int y = 177; y <= 184; y++) {
                            level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y, 12),
                                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        }
                    }
                });
                context.waitTicks(30);
            }
            boolean throughWall = stage == 2;
            String label = stage == 0 ? "depth_open" : stage == 1 ? "depth_wall" : "depth_xray";
            context.runOnClient(client -> {
                client.player.setYRot(180);
                client.player.yRotO = 180;
                client.player.setXRot(0);
                client.player.xRotO = 0;
                changes = List.of(new BlockChange(8, 180, 8, BlockChange.Kind.ADDED));
                name = label;
                xray = throughWall;
                frame = 0;
                inputStarted = System.nanoTime();
                observedReady = false;
                active = true;
            });
            context.waitFor(client -> frame >= 20 && pending.isEmpty(), 1200);
            Path screenshot = takeHighlightScreenshot(context, "lumi-compare-" + label);
            try {
                var image = ImageIO.read(screenshot.toFile());
                int highlighted = 0;
                for (int y = Math.max(0, image.getHeight() / 2 - 64);
                        y < Math.min(image.getHeight(), image.getHeight() / 2 + 64); y++) {
                    for (int x = Math.max(0, image.getWidth() / 2 - 64);
                            x < Math.min(image.getWidth(), image.getWidth() / 2 + 64); x++) {
                        if (colored(image.getRGB(x, y))) highlighted++;
                    }
                }
                if (stage == 1 ? highlighted != 0 : highlighted < 100) {
                    throw new AssertionError(label + " has " + highlighted
                            + " highlight pixels in the controlled center region: " + screenshot);
                }
            } catch (java.io.IOException failed) {
                throw new IllegalStateException("Cannot inspect depth screenshot", failed);
            }
            context.runOnClient(client -> active = false);
        }
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

    private record Pending(LumiGpuTimer query, String prefix) { }

    private static Path takeHighlightScreenshot(ClientGameTestContext context, String name) {
        boolean hidden = context.computeOnClient(client -> client.options.hideGui);
        context.runOnClient(client -> setHudHidden(client, true));
        try {
            context.waitTicks(2);
            return context.takeScreenshot(name);
        } finally {
            context.runOnClient(client -> setHudHidden(client, hidden));
        }
    }

    private static void setHudHidden(net.minecraft.client.Minecraft client, boolean hidden) {
        //? if >=26.2 {
        /*if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
        *///?} else {
        client.options.hideGui = hidden;
        //?}
    }

    private static void assertHighlight(Path screenshot) {
        try {
            var image = ImageIO.read(screenshot.toFile());
            int colored = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                    if ((red > blue + 40 && red > green + 40)
                            || (green > red + 40 && green > blue + 40)) colored++;
                }
            }
            if (colored < 1000) throw new AssertionError("Compare highlight is absent: " + screenshot);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("Cannot inspect Compare screenshot", failed);
        }
    }

    private static void assertSameHighlight(Path cached, Path transientDraw) {
        try {
            var before = ImageIO.read(cached.toFile());
            var after = ImageIO.read(transientDraw.toFile());
            int shared = 0, total = 0;
            for (int y = 0; y < before.getHeight(); y++) {
                for (int x = 0; x < before.getWidth(); x++) {
                    boolean first = colored(before.getRGB(x, y));
                    boolean second = colored(after.getRGB(x, y));
                    if (first || second) total++;
                    if (first && second) shared++;
                }
            }
            if (total == 0 || shared < total * 0.99) {
                throw new AssertionError("Transient Compare lost visible geometry: " + shared + "/" + total);
            }
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("Cannot compare cached/transient highlights", failed);
        }
    }

    private static boolean colored(int rgb) {
        int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
        return (red > blue + 40 && red > green + 40)
                || (green > red + 40 && green > blue + 40);
    }
}
