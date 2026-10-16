package io.github.lumi.observation;

import io.github.lumi.client.ui.LumiHotkeyScreen;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Checks Lumi's real window layout and player-JAR origin without a GameTest runtime. */
final class NativeLumiViewportChecks {
    private int[] previous;
    private int previousWidth;
    private int previousHeight;

    NativeLumiViewportChecks() {
        try {
            var paths = FabricLoader.getInstance().getModContainer("lumi").orElseThrow().getOrigin().getPaths();
            if (paths.size() != 1 || !Files.isRegularFile(paths.getFirst())) {
                throw new IllegalStateException("Native Lumi viewport requires a distributable JAR");
            }
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(paths.getFirst()), hash)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            if (!HexFormat.of().formatHex(hash.digest()).equals(System.getProperty("lumi.viewport.artifactHash"))) {
                throw new IllegalStateException("Native viewport loaded a different player JAR");
            }
        } catch (IOException | NoSuchAlgorithmException failed) {
            throw new IllegalStateException("Cannot identify native viewport player JAR", failed);
        }
    }

    Screen open(Minecraft client) {
        Screen screen = new LumiHotkeyScreen(client.screen, List.of());
        client.setScreen(screen);
        return screen;
    }

    void verify(Minecraft client, int width, int height) {
        Screen screen = client.screen;
        if (screen.width != (width + 1) / 2 || screen.height != (height + 1) / 2) {
            throw new IllegalStateException("Native Lumi viewport has incorrect fixed-scale dimensions: "
                    + screen.width + "x" + screen.height);
        }
        String label = Component.translatable("luma.action.close").getString();
        Button close = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
        int[] layout = {screen.width, screen.height, close.getX(), close.getY(), close.getWidth(), close.getHeight()};
        if (previous != null && previousWidth == width && previousHeight == height && !Arrays.equals(previous, layout)) {
            throw new IllegalStateException("Native Lumi controls moved with the Minecraft GUI scale");
        }
        previous = layout;
        previousWidth = width;
        previousHeight = height;
    }
}
