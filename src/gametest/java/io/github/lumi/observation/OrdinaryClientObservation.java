package io.github.lumi.observation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Opt-in ordinary-client measurement, packaged without GameTest APIs or mixins. */
public final class OrdinaryClientObservation implements ClientModInitializer {
    private volatile RuntimeObservation.ClientState state =
            new RuntimeObservation.ClientState(0, false, false, "startup", false);
    private final AtomicLong serverTicks = new AtomicLong();
    private volatile boolean simulationFrozen;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("lumi.observation.enabled")) {
            throw new IllegalStateException("Ordinary observation requires explicit enablement");
        }
        try {
            Properties metadata = identifyRuntime();
            Minecraft client = Minecraft.getInstance();
            RuntimeObservation observation = new RuntimeObservation(metadata,
                    () -> state, serverTicks::get, () -> client.execute(client::stop));
            ClientTickEvents.END_CLIENT_TICK.register(ticking -> state =
                    new RuntimeObservation.ClientState(ticking.getFps(),
                            ticking.level != null && ticking.player != null
                                    && ticking.getSingleplayerServer() != null,
                            ticking.isPaused(), ticking.screen == null ? "none" : ticking.screen.getClass().getName(),
                            simulationFrozen));
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                serverTicks.incrementAndGet();
                simulationFrozen = server.tickRateManager().isFrozen();
            });
            observation.start();
        } catch (IOException | NoSuchAlgorithmException failed) {
            throw new IllegalStateException("Cannot start ordinary observation", failed);
        }
    }

    private Properties identifyRuntime() throws IOException, NoSuchAlgorithmException {
        FabricLoader loader = FabricLoader.getInstance();
        boolean expected = Boolean.parseBoolean(System.getProperty("lumi.observation.lumi"));
        if (loader.isModLoaded("lumi") != expected || loader.isModLoaded("lumi-gametest")
                || loader.isModLoaded("fabric-client-gametest-api-v1") || loader.isModLoaded("fabric-gametest-api-v1")) {
            throw new IllegalStateException("Ordinary observation loaded unexpected Lumi/GameTest mods");
        }
        Properties metadata = new Properties();
        metadata.setProperty("lumi", Boolean.toString(expected));
        metadata.setProperty("java", Runtime.version().toString());
        metadata.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        metadata.setProperty("maxHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
        String game = loader.getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (!game.equals(System.getProperty("lumi.observation.game"))) {
            throw new IllegalStateException("Ordinary observation loaded a different game: " + game);
        }
        metadata.setProperty("game", game);
        for (var mod : loader.getAllMods()) {
            metadata.setProperty("mod." + mod.getMetadata().getId(),
                    mod.getMetadata().getVersion().getFriendlyString());
        }
        if (expected) {
            var origins = loader.getModContainer("lumi").orElseThrow().getOrigin().getPaths();
            if (origins.size() != 1 || !Files.isRegularFile(origins.getFirst())) {
                throw new IllegalStateException("Ordinary observation requires a distributable Lumi JAR");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(origins.getFirst())) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (!hash.equals(System.getProperty("lumi.observation.artifactHash"))) {
                throw new IllegalStateException("Ordinary observation loaded a different player JAR");
            }
            metadata.setProperty("artifactHash", hash);
        }
        return metadata;
    }
}
