package io.github.luma.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureGuardrailsTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path CLIENT_SOURCES = Path.of("src/client/java");

    @Test
    void domainAndStorageDoNotImportClientOrUiLayers() throws IOException {
        List<Path> offenders = javaFiles(
                MAIN_SOURCES.resolve("io/github/luma/domain"),
                MAIN_SOURCES.resolve("io/github/luma/storage")
        ).stream()
                .filter(path -> importsAny(path, "io.github.luma.client", "io.github.luma.ui"))
                .toList();

        assertTrue(offenders.isEmpty(), "Core layers must not import client/UI code: " + offenders);
    }

    @Test
    void mixinsDoNotReachIntoStorageRepositories() throws IOException {
        List<Path> offenders = javaFiles(MAIN_SOURCES.resolve("io/github/luma/mixin")).stream()
                .filter(path -> importsAny(path, "io.github.luma.storage"))
                .toList();

        assertTrue(offenders.isEmpty(), "Mixins must delegate instead of touching storage directly: " + offenders);
    }

    @Test
    void hotPathClassesDoNotGrowBeforeTheyAreSplit() throws IOException {
        Map<Path, Integer> limits = Map.of(
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world/WorldOperationManager.java"), 2000,
                MAIN_SOURCES.resolve("io/github/luma/domain/service/RestoreService.java"), 2065,
                MAIN_SOURCES.resolve("io/github/luma/minecraft/capture/HistoryCaptureManager.java"), 1815,
                MAIN_SOURCES.resolve("io/github/luma/storage/repository/PatchDataRepository.java"), 215
        );

        List<String> offenders = limits.entrySet().stream()
                .filter(entry -> lineCount(entry.getKey()) > entry.getValue())
                .map(entry -> entry.getKey() + " has " + lineCount(entry.getKey()) + " lines, limit " + entry.getValue())
                .toList();

        assertTrue(offenders.isEmpty(), "Hot-path classes must shrink during stabilization, not grow: " + offenders);
    }

    @Test
    void coreCodeAvoidsNewHelperOrUtilsDumpClasses() throws IOException {
        List<Path> offenders = javaFiles(
                MAIN_SOURCES.resolve("io/github/luma/domain"),
                MAIN_SOURCES.resolve("io/github/luma/storage/repository"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/capture"),
                MAIN_SOURCES.resolve("io/github/luma/minecraft/world")
        ).stream()
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.endsWith("Helper.java") || fileName.endsWith("Utils.java");
                })
                .toList();

        assertTrue(offenders.isEmpty(), "Use owner classes with domain names instead of Helper/Utils dumps: " + offenders);
    }

    @Test
    void clientCodeDoesNotImportStorageRepositoriesDirectly() throws IOException {
        List<Path> offenders = javaFiles(CLIENT_SOURCES.resolve("io/github/luma")).stream()
                .filter(path -> importsAny(path, "io.github.luma.storage.repository"))
                .filter(path -> !isAllowedClientStorageAdapter(path))
                .toList();

        assertTrue(offenders.isEmpty(), "Client code must reach storage through controllers/services: " + offenders);
    }

    private static List<Path> javaFiles(Path... roots) throws IOException {
        try (Stream<Path> stream = Stream.of(roots)
                .filter(Files::exists)
                .flatMap(root -> {
                    try {
                        return Files.walk(root);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static boolean importsAny(Path path, String... packagePrefixes) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        for (String packagePrefix : packagePrefixes) {
            if (source.contains("import " + packagePrefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedClientStorageAdapter(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith("src/client/java/io/github/luma/client/preview/PreviewCaptureCoordinator.java");
    }

    private static long lineCount(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.count();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
