package io.github.lumi.client.onboarding;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/** One local bit controlling first-run onboarding; replay remains always available. */
public final class ClientOnboardingStateRepository {
    private final Path file;

    public ClientOnboardingStateRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve("lumi-onboarding"));
    }

    public ClientOnboardingStateRepository(Path file) {
        this.file = file;
    }

    public boolean completed() {
        try {
            return Files.exists(file) && Files.readString(file).trim().equals("1");
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi onboarding state", failed);
            return false;
        }
    }

    public void markCompleted() {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".lumi-onboarding-", ".tmp");
            Files.writeString(temporary, "1\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi onboarding state", failed);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a client preference temporary.
                }
            }
        }
    }
}
