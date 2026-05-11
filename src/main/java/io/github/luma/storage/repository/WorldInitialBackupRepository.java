package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.StoragePathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

public final class WorldInitialBackupRepository {

    private static final String BACKUP_DIR = "pre-mod-backup";
    private static final String CHUNKS_DIR = "chunks";
    private static final String STAGING_DIR = "staging";
    private static final String REPLACED_MANIFEST_PREFIX = "manifest.replaced-";
    private static final String REPLACED_CHUNKS_PREFIX = "chunks.replaced-";
    private static final String JSON_SUFFIX = ".json";
    private static final int BACKUP_COMPRESSION_LEVEL = Deflater.DEFAULT_COMPRESSION;

    public boolean completedForSeed(Path worldRoot, long seed) throws IOException {
        return this.load(worldRoot)
                .map(manifest -> manifest.completedForSeed(seed))
                .orElse(false);
    }

    public boolean hasCompletedBackup(Path worldRoot) throws IOException {
        return this.load(worldRoot)
                .map(manifest -> manifest.completedAt() != null)
                .orElse(false);
    }

    public Optional<WorldInitialBackupManifest> load(Path worldRoot) throws IOException {
        Path manifest = this.manifestFile(worldRoot);
        if (!Files.exists(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(GsonProvider.gson().fromJson(
                    Files.readString(manifest, StandardCharsets.UTF_8),
                    WorldInitialBackupManifest.class
            ));
        } catch (JsonSyntaxException exception) {
            StorageIo.quarantineCorruptedFile(manifest, exception, "malformed pre-mod backup manifest");
            return Optional.empty();
        }
    }

    public void save(Path worldRoot, WorldInitialBackupManifest manifest) throws IOException {
        StorageIo.writeAtomically(this.manifestFile(worldRoot), output -> output.write(
                GsonProvider.gson().toJson(manifest).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public BackupAttempt beginBackupAttempt(Path worldRoot) throws IOException {
        Path backupRoot = this.backupRoot(worldRoot);
        this.cleanupIncompleteBackups(worldRoot);
        Path attemptRoot = backupRoot.resolve(STAGING_DIR).resolve("attempt-" + UUID.randomUUID());
        Files.createDirectories(attemptRoot.resolve(CHUNKS_DIR));
        return new BackupAttempt(backupRoot, attemptRoot);
    }

    public void abortBackupAttempt(BackupAttempt attempt) throws IOException {
        if (attempt != null) {
            this.deleteRecursively(attempt.backupRoot(), attempt.root());
        }
    }

    public void commitBackupAttempt(
            Path worldRoot,
            BackupAttempt attempt,
            WorldInitialBackupManifest manifest
    ) throws IOException {
        Objects.requireNonNull(attempt, "attempt");
        String transactionId = UUID.randomUUID().toString();
        Path manifestFile = this.manifestFile(worldRoot);
        Path replacedManifest = null;
        Path replacedChunks = null;
        boolean chunksPublished = false;
        boolean manifestCommitted = false;

        Files.createDirectories(attempt.backupRoot());
        if (Files.exists(manifestFile)) {
            replacedManifest = attempt.backupRoot().resolve(REPLACED_MANIFEST_PREFIX + transactionId + JSON_SUFFIX);
            this.movePath(manifestFile, replacedManifest);
        }

        try {
            replacedChunks = this.publishAttemptChunks(worldRoot, attempt, transactionId);
            chunksPublished = true;
            this.save(worldRoot, manifest);
            manifestCommitted = true;
            if (replacedManifest != null) {
                Files.deleteIfExists(replacedManifest);
            }
            this.deleteRecursivelyIfExists(attempt.backupRoot(), replacedChunks);
            this.abortBackupAttempt(attempt);
            this.cleanupIncompleteBackups(worldRoot);
        } catch (IOException | RuntimeException exception) {
            if (!manifestCommitted) {
                this.rollbackFailedCommit(worldRoot, attempt, replacedManifest, replacedChunks, chunksPublished, exception);
            }
            throw exception;
        }
    }

    public long writeChunk(Path worldRoot, String dimensionId, ChunkPoint chunk, byte[] nbtBytes) throws IOException {
        return this.writeChunk(worldRoot, dimensionId, chunk, nbtBytes, Long.MAX_VALUE).compressedBytes();
    }

    public ChunkWriteResult writeChunk(
            BackupAttempt attempt,
            String dimensionId,
            ChunkPoint chunk,
            byte[] nbtBytes,
            long maxCompressedBytes
    ) throws IOException {
        Objects.requireNonNull(attempt, "attempt");
        return this.writeChunkToChunksRoot(
                this.chunksRoot(attempt),
                dimensionId,
                chunk,
                nbtBytes,
                maxCompressedBytes,
                false
        );
    }

    public ChunkWriteResult writeChunk(
            Path worldRoot,
            String dimensionId,
            ChunkPoint chunk,
            byte[] nbtBytes,
            long maxCompressedBytes
    ) throws IOException {
        return this.writeChunkToChunksRoot(
                this.chunksRoot(worldRoot),
                dimensionId,
                chunk,
                nbtBytes,
                maxCompressedBytes,
                true
        );
    }

    public Path backupRoot(Path worldRoot) {
        return worldRoot.resolve("lumi").resolve(BACKUP_DIR);
    }

    private Path manifestFile(Path worldRoot) {
        return this.backupRoot(worldRoot).resolve("manifest.json");
    }

    private Path chunksRoot(Path worldRoot) {
        return this.backupRoot(worldRoot).resolve(CHUNKS_DIR);
    }

    private Path chunksRoot(BackupAttempt attempt) {
        return attempt.root().resolve(CHUNKS_DIR);
    }

    private ChunkWriteResult writeChunkToChunksRoot(
            Path chunksRoot,
            String dimensionId,
            ChunkPoint chunk,
            byte[] nbtBytes,
            long maxCompressedBytes,
            boolean durable
    ) throws IOException {
        if (maxCompressedBytes <= 0L) {
            return new ChunkWriteResult(false, 0L);
        }
        byte[] compressed = this.compress(nbtBytes == null ? new byte[0] : nbtBytes);
        if (compressed.length > maxCompressedBytes) {
            return new ChunkWriteResult(false, compressed.length);
        }
        Path file = this.chunkFile(chunksRoot, dimensionId, chunk);
        if (durable) {
            StorageIo.writeAtomically(file, output -> output.write(compressed));
        } else {
            this.writeStagedChunk(file, compressed);
        }
        return new ChunkWriteResult(true, compressed.length);
    }

    private void writeStagedChunk(Path file, byte[] compressed) throws IOException {
        Files.createDirectories(file.getParent());
        Path tempFile = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            Files.write(tempFile, compressed, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            this.movePath(tempFile, file);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private Path chunkFile(Path chunksRoot, String dimensionId, ChunkPoint chunk) {
        String dimensionFolder = StoragePathPolicy.safeFolderName(dimensionId.replace(':', '_').replace('/', '_'));
        return chunksRoot
                .resolve(dimensionFolder)
                .resolve("chunk_" + chunk.x() + "_" + chunk.z() + ".nbt.gz");
    }

    private void cleanupIncompleteBackups(Path worldRoot) throws IOException {
        Path backupRoot = this.backupRoot(worldRoot);
        this.recoverInterruptedReplacement(worldRoot);
        this.deleteRecursivelyIfExists(backupRoot, backupRoot.resolve(STAGING_DIR));
        if (!Files.isDirectory(backupRoot)) {
            return;
        }
        try (var files = Files.list(backupRoot)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(REPLACED_CHUNKS_PREFIX)
                        || (name.startsWith(REPLACED_MANIFEST_PREFIX) && name.endsWith(JSON_SUFFIX))) {
                    this.deleteRecursively(backupRoot, file);
                }
            }
        }
    }

    private void recoverInterruptedReplacement(Path worldRoot) throws IOException {
        Path manifestFile = this.manifestFile(worldRoot);
        if (Files.exists(manifestFile)) {
            return;
        }
        Path backupRoot = this.backupRoot(worldRoot);
        if (!Files.isDirectory(backupRoot)) {
            return;
        }
        Optional<Path> replacementManifest;
        try (var files = Files.list(backupRoot)) {
            replacementManifest = files
                    .filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(REPLACED_MANIFEST_PREFIX) && name.endsWith(JSON_SUFFIX);
                    })
                    .sorted(Comparator.reverseOrder())
                    .findFirst();
        }
        if (replacementManifest.isEmpty()) {
            return;
        }

        String transactionId = replacementManifest.get().getFileName().toString();
        transactionId = transactionId.substring(
                REPLACED_MANIFEST_PREFIX.length(),
                transactionId.length() - JSON_SUFFIX.length()
        );
        Path replacedChunks = backupRoot.resolve(REPLACED_CHUNKS_PREFIX + transactionId);
        Path finalChunks = this.chunksRoot(worldRoot);
        if (Files.exists(replacedChunks)) {
            this.deleteRecursivelyIfExists(backupRoot, finalChunks);
            this.movePath(replacedChunks, finalChunks);
        }
        this.movePath(replacementManifest.get(), manifestFile);
    }

    private Path publishAttemptChunks(Path worldRoot, BackupAttempt attempt, String transactionId) throws IOException {
        Path stagedChunks = this.chunksRoot(attempt);
        Path finalChunks = this.chunksRoot(worldRoot);
        Path replacedChunks = null;
        if (Files.exists(finalChunks)) {
            replacedChunks = attempt.backupRoot().resolve(REPLACED_CHUNKS_PREFIX + transactionId);
            this.movePath(finalChunks, replacedChunks);
        }
        try {
            this.movePath(stagedChunks, finalChunks);
            return replacedChunks;
        } catch (IOException exception) {
            if (replacedChunks != null && !Files.exists(finalChunks)) {
                try {
                    this.movePath(replacedChunks, finalChunks);
                } catch (IOException restoreException) {
                    exception.addSuppressed(restoreException);
                }
            }
            throw exception;
        }
    }

    private void rollbackFailedCommit(
            Path worldRoot,
            BackupAttempt attempt,
            Path replacedManifest,
            Path replacedChunks,
            boolean chunksPublished,
            Throwable failure
    ) {
        try {
            if (chunksPublished) {
                Path finalChunks = this.chunksRoot(worldRoot);
                this.deleteRecursivelyIfExists(attempt.backupRoot(), finalChunks);
                if (replacedChunks != null && Files.exists(replacedChunks)) {
                    this.movePath(replacedChunks, finalChunks);
                }
            }
            Path manifestFile = this.manifestFile(worldRoot);
            if (replacedManifest != null && Files.exists(replacedManifest) && !Files.exists(manifestFile)) {
                this.movePath(replacedManifest, manifestFile);
            }
        } catch (IOException rollbackException) {
            failure.addSuppressed(rollbackException);
        }
    }

    private void movePath(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteRecursivelyIfExists(Path backupRoot, Path target) throws IOException {
        if (target != null && Files.exists(target)) {
            this.deleteRecursively(backupRoot, target);
        }
    }

    private void deleteRecursively(Path backupRoot, Path target) throws IOException {
        Path normalizedRoot = backupRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("Refusing to delete outside Lumi pre-mod backup root: " + target);
        }
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private byte[] compress(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new BackupGzipOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }

    private static final class BackupGzipOutputStream extends GZIPOutputStream {

        private BackupGzipOutputStream(ByteArrayOutputStream output) throws IOException {
            super(output);
            this.def.setLevel(BACKUP_COMPRESSION_LEVEL);
        }
    }

    public record ChunkWriteResult(boolean written, long compressedBytes) {
    }

    public record BackupAttempt(Path backupRoot, Path root) {

        public BackupAttempt {
            Objects.requireNonNull(backupRoot, "backupRoot");
            Objects.requireNonNull(root, "root");
        }
    }
}
