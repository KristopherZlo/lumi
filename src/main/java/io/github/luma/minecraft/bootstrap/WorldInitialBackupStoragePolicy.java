package io.github.luma.minecraft.bootstrap;

final class WorldInitialBackupStoragePolicy {

    static final String MAX_MIB_PROPERTY = "lumi.preModBackup.maxMiB";
    private static final long DEFAULT_MAX_COMPRESSED_BYTES = 0L;

    long maxCompressedBytes() {
        String configured = System.getProperty(MAX_MIB_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_COMPRESSED_BYTES;
        }
        try {
            long mib = Long.parseLong(configured.trim());
            if (mib <= 0L) {
                return 0L;
            }
            return Math.multiplyExact(mib, 1024L * 1024L);
        } catch (ArithmeticException | NumberFormatException exception) {
            return DEFAULT_MAX_COMPRESSED_BYTES;
        }
    }

    long remainingBytes(long writtenBytes) {
        return Math.max(0L, this.maxCompressedBytes() - Math.max(0L, writtenBytes));
    }
}
