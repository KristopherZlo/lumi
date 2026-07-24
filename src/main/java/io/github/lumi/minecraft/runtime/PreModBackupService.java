package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.mixin.MinecraftServerAccessor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Optional one-time vanilla backup before Lumi creates history for a world. */
public final class PreModBackupService {
    public static final String MAX_MIB_PROPERTY = "lumi.preModBackup.maxMiB";
    private static final Path HISTORY = Path.of("lumi", "history");
    private static final Path MARKER = Path.of("lumi", "pre-mod-backup.complete");

    private PreModBackupService() {
    }

    public static void run(MinecraftServer server) throws IOException {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize();
        run(worldRoot, System.getProperty(MAX_MIB_PROPERTY),
                () -> ((MinecraftServerAccessor) server)
                        .lumi$storageSource().makeWorldBackup());
    }

    static boolean run(Path worldRoot, String configuredMaxMiB, Backup backup)
            throws IOException {
        if (configuredMaxMiB == null) {
            return false;
        }
        long maxBytes = maxBytes(configuredMaxMiB);
        Path root = worldRoot.toAbsolutePath().normalize();
        Path marker = root.resolve(MARKER);
        if (Files.exists(marker) || Files.exists(root.resolve(HISTORY))) {
            return false;
        }
        long inputBytes = boundedSize(root, maxBytes);
        LumiMod.LOGGER.info(
                "Creating optional pre-Lumi vanilla backup ({} bytes scanned)",
                inputBytes);
        long backupBytes = backup.create();
        writeMarker(marker, inputBytes, backupBytes);
        LumiMod.LOGGER.info("Pre-Lumi vanilla backup complete ({} bytes)", backupBytes);
        return true;
    }

    private static long maxBytes(String value) {
        try {
            long maxMiB = Long.parseLong(value);
            if (maxMiB <= 0) {
                throw new IllegalArgumentException(
                        MAX_MIB_PROPERTY + " must be a positive integer");
            }
            return Math.multiplyExact(maxMiB, 1024L * 1024L);
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException(
                    MAX_MIB_PROPERTY + " must be a positive integer", invalid);
        }
    }

    private static long boundedSize(Path root, long maxBytes) throws IOException {
        long[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                if (file.endsWith("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    total[0] = Math.addExact(total[0], attributes.size());
                } catch (ArithmeticException overflow) {
                    throw tooLarge(maxBytes);
                }
                if (total[0] > maxBytes) {
                    throw tooLarge(maxBytes);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static IOException tooLarge(long maxBytes) {
        return new IOException("World exceeds " + MAX_MIB_PROPERTY + "="
                + (maxBytes / (1024L * 1024L)) + "; no backup was created");
    }

    private static void writeMarker(Path marker, long inputBytes, long backupBytes)
            throws IOException {
        Files.createDirectories(marker.getParent());
        byte[] content = ("inputBytes=" + inputBytes + "\nbackupBytes="
                + backupBytes + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface Backup {
        long create() throws IOException;
    }
}
