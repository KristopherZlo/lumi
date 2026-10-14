package io.github.lumi.buildconfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftArtifactVerifierTest {
    @TempDir Path directory;
    private final MinecraftArtifactVerifier verifier = new MinecraftArtifactVerifier();

    @Test
    void verifiesOnlyLibrariesDeclaredForFabricLoading() throws Exception {
        var target = target();
        assertDoesNotThrow(() -> verifier.verify(artifact(true, 65), target, "test"));
        assertThrows(IOException.class, () -> verifier.verify(artifact(false, 65), target, "test"));
    }

    @Test
    void refusesModernBytecodeInTheSharedCoreAndWrongReleaseMetadata() throws Exception {
        var target = target();
        assertThrows(IOException.class, () -> verifier.verify(artifact(true, 69), target, "test"));
        assertThrows(IOException.class, () -> verifier.verify(artifact(true, 65), target, "other"));
    }

    private MinecraftBuildTarget target() throws IOException {
        return new MinecraftTargetCatalogReader().read(Path.of("../gradle/minecraft-targets.json"))
                .defaultTarget();
    }

    private Path artifact(boolean declared, int coreMajor) throws IOException {
        String metadata = """
                {"version":"test","depends":{"minecraft":["1.21.11"],"java":">=21"},"jars":%s}
                """.formatted(declared ? "[{\"file\":\"META-INF/jars/core.jar\"}]" : "[]");
        String mixins = "{\"required\":true,\"compatibilityLevel\":\"JAVA_21\"}";
        byte[] library = zip(Map.of(
                "io/github/lumi/domain/service/SaveService.class", classHeader(coreMajor),
                "net/jpountz/lz4/LZ4Factory.class", classHeader(52)));
        Path path = directory.resolve("candidate.jar");
        Files.write(path, zip(Map.of(
                "fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8),
                "lumi.mixins.json", mixins.getBytes(StandardCharsets.UTF_8),
                "io/github/lumi/LumiMod.class", classHeader(65),
                "META-INF/jars/core.jar", library)));
        return path;
    }

    private static byte[] classHeader(int major) {
        return new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, (byte) major};
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
