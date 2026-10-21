package io.github.lumi.client.state;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Stores the local UI accent independently of server and world settings. */
public final class ClientAccentRepository {
    public static final int DEFAULT = 0xffd9b86c;
    private final Path file;

    public ClientAccentRepository(Path file) {
        this.file = file.toAbsolutePath();
    }

    public int load() {
        try {
            if (!Files.exists(file)) return DEFAULT;
            if (Files.size(file) > 16) throw new IOException("Invalid accent preference size");
            return parse(Files.readString(file).trim());
        } catch (IOException | IllegalArgumentException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi accent preference; using default", failed);
            return DEFAULT;
        }
    }

    public void save(int color) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".lumi-accent-", ".tmp");
        try {
            Files.writeString(temporary, hex(color) + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static int parse(String value) {
        if ("default".equalsIgnoreCase(value)) return DEFAULT;
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (!hex.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Expected RRGGBB");
        return 0xff000000 | Integer.parseInt(hex, 16);
    }

    public static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
    }
}
