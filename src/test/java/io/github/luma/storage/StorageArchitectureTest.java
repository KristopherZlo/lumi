package io.github.luma.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageArchitectureTest {

    @Test
    void storageDoesNotImportMinecraftWorldLayer() throws IOException {
        Path storageRoot = Path.of("src/main/java/io/github/luma/storage");
        List<Path> offenders;
        try (var stream = Files.walk(storageRoot)) {
            offenders = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> importsMinecraftWorld(path))
                    .toList();
        }

        assertTrue(
                offenders.isEmpty(),
                () -> "Storage files must not import minecraft.world classes: " + offenders
        );
    }

    private static boolean importsMinecraftWorld(Path path) {
        try {
            return Files.readString(path).contains("io.github.luma.minecraft.world.");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
