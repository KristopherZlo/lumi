package io.github.lumi.client.state;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

/** Client-local opt-in for copyable operation statistics in Minecraft chat. */
public final class ClientDeveloperMode {
    private final Path file;
    private boolean enabled;

    public ClientDeveloperMode() {
        this(FabricLoader.getInstance().getConfigDir()
                .resolve("lumi-developer-mode"));
    }

    ClientDeveloperMode(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        enabled = load();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    file, value ? "1\n" : "0\n", StandardCharsets.UTF_8);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi developer mode", failed);
        }
    }

    private boolean load() {
        try {
            return Files.exists(file) && Files.size(file) <= 2
                    && Files.readString(file, StandardCharsets.UTF_8)
                            .trim().equals("1");
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi developer mode", failed);
            return false;
        }
    }
}
