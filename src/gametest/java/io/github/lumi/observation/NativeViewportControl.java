package io.github.lumi.observation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Exercises the real window backend without Fabric's GameTest window virtualization. */
public final class NativeViewportControl implements ClientModInitializer {
    private static final int[][] SIZES = {{1280, 720}, {1920, 1080}, {853, 481}};
    private static final int[] SCALES = {1, 2, 3, 4, 0};
    private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    private int step;
    private int frames;
    private boolean completed;
    private NativeLumiViewportChecks lumi;
    private Screen page;

    @Override public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        for (String forbidden : new String[] {"worldedit", "axiom", "lumi-gametest",
                "fabric-client-gametest-api-v1", "fabric-gametest-api-v1"}) {
            if (loader.isModLoaded(forbidden)) throw new IllegalStateException("Unexpected control mod: " + forbidden);
        }
        if (!Boolean.getBoolean("lumi.viewport.enabled")) throw new IllegalStateException("Control requires enablement");
        boolean expectedLumi = Boolean.getBoolean("lumi.viewport.lumi");
        if (loader.isModLoaded("lumi") != expectedLumi) throw new IllegalStateException("Unexpected Lumi presence");
        if (expectedLumi) lumi = new NativeLumiViewportChecks();
        System.out.println("Native viewport runtime: " + Runtime.version() + ";java=" + System.getProperty("java.home"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (completed) return;
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Native viewport control timed out");
            if (page == null) {
                if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
                page = lumi == null ? client.screen : lumi.open(client);
            }
            if (client.screen != page) throw new IllegalStateException("Native viewport screen changed unexpectedly");
            if (++frames < 40) return;
            frames = 0;
            if (step > 0) {
                int[] previous = SIZES[(step - 1) / SCALES.length];
                if (client.getWindow().getWidth() != previous[0] || client.getWindow().getHeight() != previous[1]) {
                    throw new IllegalStateException("Native window did not reach requested size at step " + step
                            + ": " + client.getWindow().getWidth() + "x" + client.getWindow().getHeight());
                }
                if (lumi != null) lumi.verify(client, previous[0], previous[1]);
                System.out.println("Native viewport completed step " + step + "/" + SIZES.length * SCALES.length);
            }
            if (step == SIZES.length * SCALES.length) {
                String game = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                finish(client, game + (lumi == null ? "\nnative-viewport\n" : "\nnative-lumi-viewport\n") + step
                        + (lumi == null ? "" : "\n" + System.getProperty("lumi.viewport.artifactHash")));
                return;
            }
            int[] size = SIZES[step / SCALES.length];
            client.getWindow().setWindowed(size[0], size[1]);
            client.options.guiScale().set(SCALES[step % SCALES.length]);
            client.options.framerateLimit().set(30);
            client.resizeDisplay();
            step++;
        } catch (RuntimeException failure) {
            finish(client, "failed\n" + failure);
            throw failure;
        }
    }

    private void finish(Minecraft client, String result) {
        completed = true;
        try {
            Files.writeString(Path.of(System.getProperty("lumi.viewport.completion")), result,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist native viewport result", failed);
        } finally {
            client.execute(client::stop);
        }
    }
}
