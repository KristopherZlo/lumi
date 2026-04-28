package io.github.luma.storage.repository;

import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.GsonProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOriginRepositoryTest {

    @TempDir
    Path tempDir;

    private final WorldOriginRepository repository = new WorldOriginRepository();

    @Test
    void loadFileQuarantinesMalformedManifest() throws Exception {
        Path manifest = this.tempDir.resolve("world-origin.json");
        Files.writeString(manifest, "{\"schemaVersion\":2}\"}", StandardCharsets.UTF_8);

        assertTrue(this.repository.loadFile(manifest).isEmpty());
        assertFalse(Files.exists(manifest));
        try (var files = Files.list(this.tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("world-origin.json.corrupt-")));
        }
    }

    @Test
    void loadFileKeepsValidManifest() throws Exception {
        Path manifest = this.tempDir.resolve("world-origin.json");
        WorldOriginInfo info = new WorldOriginInfo(
                2,
                "New World",
                "1.21.11",
                4671,
                123L,
                false,
                "datapacks",
                Map.of("minecraft:overworld", new WorldOriginInfo.DimensionOrigin(
                        "minecraft:overworld",
                        "noise",
                        "biomes",
                        63,
                        "generator"
                )),
                Instant.parse("2026-04-28T08:00:00Z"),
                Instant.parse("2026-04-28T08:00:00Z")
        );
        Files.writeString(manifest, GsonProvider.gson().toJson(info), StandardCharsets.UTF_8);

        WorldOriginInfo loaded = this.repository.loadFile(manifest).orElseThrow();

        assertEquals("New World", loaded.levelName());
        assertTrue(Files.exists(manifest));
    }
}
