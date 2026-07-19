package io.github.lumi.storage.packageformat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.PackageName;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LumiPackageDirectoryTest {
    @TempDir Path world;

    @Test
    void resolvesOnlyLogicalNamesBelowTheWorldPackageDirectory() throws Exception {
        LumiPackageDirectory packages = new LumiPackageDirectory(world);

        assertEquals(world.resolve("lumi/packages").toAbsolutePath().normalize(),
                packages.ensureDirectory());

        Path clock = packages.resolve(new PackageName("clock-v2"));
        Files.createDirectories(clock.getParent());
        Files.write(clock, new byte[] {1, 2, 3});

        assertEquals(world.resolve("lumi/packages/clock-v2.lumi")
                .toAbsolutePath().normalize(), clock);
        assertEquals(java.util.List.of(
                new LumiPackageDirectory.Entry(
                        new PackageName("clock-v2"), 3, Files.getLastModifiedTime(clock).toInstant())),
                packages.list());
        assertThrows(IllegalArgumentException.class,
                () -> new PackageName("../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> new PackageName(".hidden"));
    }
}
