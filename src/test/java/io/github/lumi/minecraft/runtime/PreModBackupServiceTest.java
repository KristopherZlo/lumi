package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreModBackupServiceTest {
    @TempDir
    Path world;

    @Test
    void absentPropertyDoesNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        assertFalse(PreModBackupService.run(world, null, () -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    void createsOneBackupBeforeLumiHistoryExists() throws Exception {
        Files.writeString(world.resolve("level.dat"), "existing world");
        AtomicInteger calls = new AtomicInteger();

        assertTrue(PreModBackupService.run(world, "1", () -> {
            calls.incrementAndGet();
            return 512;
        }));
        assertFalse(PreModBackupService.run(world, "1", () -> {
            calls.incrementAndGet();
            return 512;
        }));

        assertEquals(1, calls.get());
        assertTrue(Files.exists(world.resolve("lumi/pre-mod-backup.complete")));
    }

    @Test
    void existingLumiHistoryIsNeverBackedUpAsPreMod() throws Exception {
        Files.createDirectories(world.resolve("lumi/history"));
        AtomicInteger calls = new AtomicInteger();

        assertFalse(PreModBackupService.run(world, "1", () -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    void oversizedOrInvalidLimitsAbortBeforeBackup() throws Exception {
        Files.write(world.resolve("region.mca"), new byte[1024 * 1024 + 1]);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class,
                () -> PreModBackupService.run(world, "1", () -> calls.incrementAndGet()));
        assertThrows(IllegalArgumentException.class,
                () -> PreModBackupService.run(world, "invalid", () -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }
}
