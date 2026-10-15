package io.github.lumi.gametest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.SharedConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Last entrypoint: a successful process must have reached every preceding client scenario. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiClientCompletionGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        String destination = System.getProperty("lumi.gametest.completion");
        if (destination == null) return;
        String requestedBackend = System.getProperty("lumi.gametest.graphicsBackend");
        if (requestedBackend != null) {
            //? if >=26.3 {
            /*String actualBackend = context.computeOnClient(client ->
                    com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName());
            if (!actualBackend.equalsIgnoreCase(requestedBackend)) {
                throw new AssertionError("Client used " + actualBackend + " instead of " + requestedBackend);
            }
            *///?}
            //? if <26.3 {
            throw new AssertionError("Explicit graphics backend verification requires Minecraft 26.3");
            //?}
        }
        String hash = verifyLoadedOrigin();
        try {
            var suite = LumiClientTestSuite.valueOf(System.getProperty("lumi.gametest.suite"));
            try (var file = FileChannel.open(Path.of(destination), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap((suite.name() + "\n" + hash).getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot certify packaged client completion", failed);
        }
    }

    static String verifyLoadedOrigin() {
        if (!SharedConstants.getCurrentVersion().name().equals(System.getProperty("lumi.gametest.game"))) {
            throw new AssertionError("Packaged client is running a different Minecraft version");
        }
        var origin = FabricLoader.getInstance().getModContainer("lumi").orElseThrow().getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1
                || !Files.isRegularFile(origin.getPaths().getFirst())) {
            throw new AssertionError("Packaged client must load Lumi from one distributable file");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(origin.getPaths().getFirst())));
            if (!hash.equals(System.getProperty("lumi.gametest.artifactHash"))) {
                throw new AssertionError("Client loaded a different Lumi artifact");
            }
            for (var type : java.util.List.of(io.github.lumi.LumiMod.class,
                    io.github.lumi.client.LumiClient.class, io.github.lumi.minecraft.runtime.FabricDimensionRuntime.class)) {
                Path loaded = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!Files.isRegularFile(loaded) || !hash.equals(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded))))) {
                    throw new AssertionError("Local output replaced a packaged Lumi class: " + type.getName());
                }
            }
            return hash;
        } catch (IOException | NoSuchAlgorithmException | URISyntaxException failed) {
            throw new IllegalStateException("Cannot verify packaged client origin", failed);
        }
    }
}
