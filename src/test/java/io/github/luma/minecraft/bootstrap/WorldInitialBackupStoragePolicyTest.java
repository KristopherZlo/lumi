package io.github.luma.minecraft.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
class WorldInitialBackupStoragePolicyTest {

    @Test
    void defaultsToManifestOnlyBackup() {
        String previous = System.getProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY);
        System.clearProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY);
        try {
            assertEquals(0L, new WorldInitialBackupStoragePolicy().maxCompressedBytes());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void supportsExplicitSmallDisabledOrInvalidBudget() {
        String previous = System.getProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY);
        try {
            System.setProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY, "2");
            assertEquals(2L * 1024L * 1024L, new WorldInitialBackupStoragePolicy().maxCompressedBytes());

            System.setProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY, "0");
            assertEquals(0L, new WorldInitialBackupStoragePolicy().maxCompressedBytes());

            System.setProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY, "not-a-number");
            assertEquals(0L, new WorldInitialBackupStoragePolicy().maxCompressedBytes());
        } finally {
            restoreProperty(previous);
        }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY);
        } else {
            System.setProperty(WorldInitialBackupStoragePolicy.MAX_MIB_PROPERTY, previous);
        }
    }
}
