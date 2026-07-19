package io.github.lumi.update;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

/** Atomically stores the one release version a player chose to hide. */
public final class ClientUpdatePreferenceRepository {
    private static final int MAX_VERSION_LENGTH = 32;
    private final Path file;

    public ClientUpdatePreferenceRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve("lumi-update-ignore"));
    }

    public ClientUpdatePreferenceRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public boolean ignored(String version) {
        String expected = validate(version);
        try {
            if (!Files.exists(file) || Files.size(file) > MAX_VERSION_LENGTH + 2L) {
                return false;
            }
            return Files.readString(file, StandardCharsets.UTF_8).trim()
                    .equals(expected);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi update preference", failed);
            return false;
        }
    }

    public void dismiss(String version) {
        String value = validate(version);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(),
                    ".lumi-update-ignore-", ".tmp");
            Files.writeString(temporary, value + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi update preference", failed);
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

    private static String validate(String version) {
        String value = Objects.requireNonNull(version, "version").trim();
        if (value.isEmpty() || value.length() > MAX_VERSION_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid update version");
        }
        return value;
    }
}
