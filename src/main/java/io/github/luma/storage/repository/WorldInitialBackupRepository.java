package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.StoragePathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

public final class WorldInitialBackupRepository {

    private static final String BACKUP_DIR = "pre-mod-backup";
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

    public long writeChunk(Path worldRoot, String dimensionId, ChunkPoint chunk, byte[] nbtBytes) throws IOException {
        return this.writeChunk(worldRoot, dimensionId, chunk, nbtBytes, Long.MAX_VALUE).compressedBytes();
    }

    public ChunkWriteResult writeChunk(
            Path worldRoot,
            String dimensionId,
            ChunkPoint chunk,
            byte[] nbtBytes,
            long maxCompressedBytes
    ) throws IOException {
        if (maxCompressedBytes <= 0L) {
            return new ChunkWriteResult(false, 0L);
        }
        byte[] compressed = this.compress(nbtBytes == null ? new byte[0] : nbtBytes);
        if (compressed.length > maxCompressedBytes) {
            return new ChunkWriteResult(false, compressed.length);
        }
        Path file = this.chunkFile(worldRoot, dimensionId, chunk);
        StorageIo.writeAtomically(file, output -> output.write(compressed));
        return new ChunkWriteResult(true, compressed.length);
    }

    public Path backupRoot(Path worldRoot) {
        return worldRoot.resolve("lumi").resolve(BACKUP_DIR);
    }

    private Path manifestFile(Path worldRoot) {
        return this.backupRoot(worldRoot).resolve("manifest.json");
    }

    private Path chunkFile(Path worldRoot, String dimensionId, ChunkPoint chunk) {
        String dimensionFolder = StoragePathPolicy.safeFolderName(dimensionId.replace(':', '_').replace('/', '_'));
        return this.backupRoot(worldRoot)
                .resolve("chunks")
                .resolve(dimensionFolder)
                .resolve("chunk_" + chunk.x() + "_" + chunk.z() + ".nbt.gz");
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
}
