package io.github.luma.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinPackageOwnershipTest {

    private static final Path MIXIN_SOURCE_ROOT = Path.of("src/main/java/io/github/luma/mixin");

    @Test
    void mixinPackageContainsOnlyMixinEntryPoints() throws IOException {
        List<Path> nonMixinSources;
        try (var files = Files.walk(MIXIN_SOURCE_ROOT)) {
            nonMixinSources = files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !sourceContainsMixinAnnotation(path))
                    .toList();
        }

        assertTrue(
                nonMixinSources.isEmpty(),
                () -> "Mixin-owned packages must not contain helper classes: " + nonMixinSources
        );
    }

    private static boolean sourceContainsMixinAnnotation(Path sourceFile) {
        try {
            return Files.readString(sourceFile, StandardCharsets.UTF_8).contains("@Mixin");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + sourceFile, exception);
        }
    }
}
