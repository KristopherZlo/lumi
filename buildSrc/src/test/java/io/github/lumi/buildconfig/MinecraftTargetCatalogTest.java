package io.github.lumi.buildconfig;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftTargetCatalogTest {
    @TempDir Path directory;

    @Test
    void readsTheRepositoryBaselineAndTheIsolatedModernCandidate() throws Exception {
        var catalog = new MinecraftTargetCatalogReader().read(
                Path.of("../gradle/minecraft-targets.json"));
        var target = catalog.defaultTarget();
        assertEquals("legacy-12111", target.id());
        assertEquals("1.21.11", target.minecraftVersion());
        assertEquals(List.of("1.21.11"), target.minecraftVersions());
        assertEquals(21, target.javaVersion());
        assertEquals("0.141.3+1.21.11", target.fabricApiVersion());
        assertEquals("oY3E2pzA", target.worldeditVersionId());
        assertEquals("DLVQrqv2", target.axiomVersionId());
        var modern = catalog.select("modern-261");
        assertEquals(25, modern.javaVersion());
        assertEquals(List.of("26.1"), modern.minecraftVersions());
        assertTrue(modern.axiomRuntimeJars().contains("lattice-2.0.1+26.1.jar"));
        assertTrue(modern.worldeditCuiCoordinate().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.select("modern-262"));
    }

    @Test
    void oneTargetCanDeclareSeveralExactGameReleases() {
        var target = target("modern-261", List.of("26.1", "26.1.1", "26.1.2"));
        var catalog = new MinecraftTargetCatalog(target.id(), List.of(target));
        assertSame(target, catalog.select(target.id()));
        assertEquals(3, catalog.defaultTarget().minecraftVersions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> target.minecraftVersions().add("26.2"));
    }

    @Test
    void rejectsOpenVersionRangesAndDuplicateReleases() {
        assertThrows(IllegalArgumentException.class,
                () -> target("group", List.of("26.1", ">=26.1")));
        assertThrows(IllegalArgumentException.class,
                () -> target("group", List.of("26.1", "26.1")));
    }

    @Test
    void rejectsAmbiguousOwnershipAndDuplicateTargets() {
        var first = target("first", List.of("26.1"));
        var overlap = target("second", List.of("26.1", "26.1.1"));
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftTargetCatalog("first", List.of(first, overlap)));
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftTargetCatalog("first", List.of(first, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftTargetCatalog("missing", List.of(first)));
    }

    @Test
    void rejectsACompileVersionOutsideTheDeclaredGameListAndUnsafeJarPaths() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftBuildTarget(
                "group", "26.2", List.of("26.1"), 25, "loader", "api", "we", "axiom",
                Optional.empty(), List.of("api.jar")));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftBuildTarget(
                "group", "26.1", List.of("26.1"), 25, "loader", "api", "we", "axiom",
                Optional.empty(), List.of("../api.jar")));
    }

    @Test
    void rejectsUnsupportedSchemasAndMalformedConfiguration() throws Exception {
        Path file = directory.resolve("targets.json");
        for (String json : List.of("[]", "{\"schema\":2}",
                "{\"schema\":1,\"defaultTarget\":\"missing\",\"targets\":[]}", "{")) {
            Files.writeString(file, json);
            assertThrows(IllegalArgumentException.class,
                    () -> new MinecraftTargetCatalogReader().read(file));
        }
    }

    private MinecraftBuildTarget target(String id, List<String> versions) {
        return new MinecraftBuildTarget(id, versions.getFirst(), versions, 25,
                "loader", "api", "we", "axiom", Optional.empty(), List.of("api.jar"));
    }
}
