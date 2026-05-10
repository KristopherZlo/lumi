package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.storage.StoragePathPolicy;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Restores raw chunk payloads captured before Lumi first opened an existing world.
 */
public final class WorldInitialBackupRestoreService {

    private static final Pattern CHUNK_FILE_PATTERN = Pattern.compile("^chunk_(-?\\d+)_(-?\\d+)\\.nbt\\.gz$");
    private static final int MAX_CHUNK_NBT_BYTES = 64 * 1024 * 1024;

    private final WorldInitialBackupRepository repository;

    public WorldInitialBackupRestoreService() {
        this(new WorldInitialBackupRepository());
    }

    WorldInitialBackupRestoreService(WorldInitialBackupRepository repository) {
        this.repository = repository;
    }

    public boolean hasRestorableBackup(Path worldRoot) throws IOException {
        return this.repository.hasCompletedBackup(worldRoot) && !this.restorableChunks(worldRoot).isEmpty();
    }

    public RestoreResult restore(Path worldRoot) throws IOException {
        Optional<WorldInitialBackupManifest> loaded = this.repository.load(worldRoot);
        if (loaded.isEmpty() || loaded.get().completedAt() == null) {
            throw new IOException("No completed Lumi pre-mod backup manifest exists");
        }

        List<RestorableChunk> chunks = this.restorableChunks(worldRoot, loaded.get());
        if (chunks.isEmpty()) {
            return new RestoreResult(0, 0);
        }

        int restored = 0;
        Map<RegionTarget, List<RestorableChunk>> byRegion = this.groupByRegion(worldRoot, chunks);
        for (Map.Entry<RegionTarget, List<RestorableChunk>> entry : byRegion.entrySet()) {
            RegionTarget target = entry.getKey();
            Files.createDirectories(target.regionDir());
            try (RegionFile regionFile = new RegionFile(
                    new RegionStorageInfo(loaded.get().levelName(), target.dimensionKey(), "lumi-pre-mod-restore"),
                    target.regionFile(),
                    target.regionDir(),
                    false
            )) {
                for (RestorableChunk chunk : entry.getValue()) {
                    try (DataOutputStream output = regionFile.getChunkDataOutputStream(
                            new ChunkPos(chunk.chunk().x(), chunk.chunk().z())
                    )) {
                        output.write(this.readBackupChunk(chunk.file()));
                    }
                    restored += 1;
                }
            }
        }

        return new RestoreResult(restored, chunks.size());
    }

    private List<RestorableChunk> restorableChunks(Path worldRoot) throws IOException {
        Optional<WorldInitialBackupManifest> loaded = this.repository.load(worldRoot);
        return loaded.isEmpty() ? List.of() : this.restorableChunks(worldRoot, loaded.get());
    }

    private List<RestorableChunk> restorableChunks(Path worldRoot, WorldInitialBackupManifest manifest) throws IOException {
        Path chunksRoot = this.repository.backupRoot(worldRoot).resolve("chunks");
        if (!Files.isDirectory(chunksRoot) || manifest.dimensions() == null || manifest.dimensions().isEmpty()) {
            return List.of();
        }

        List<RestorableChunk> chunks = new ArrayList<>();
        for (String dimensionId : manifest.dimensions().keySet()) {
            Path dimensionDir = chunksRoot.resolve(this.dimensionFolder(dimensionId));
            if (!Files.isDirectory(dimensionDir)) {
                continue;
            }
            try (var files = Files.list(dimensionDir)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    ChunkPoint chunk = this.parseChunkFile(file);
                    if (chunk != null) {
                        chunks.add(new RestorableChunk(dimensionId, chunk, file));
                    }
                }
            }
        }
        chunks.sort(Comparator.comparing(RestorableChunk::dimensionId)
                .thenComparingInt(chunk -> chunk.chunk().x())
                .thenComparingInt(chunk -> chunk.chunk().z()));
        return List.copyOf(chunks);
    }

    private Map<RegionTarget, List<RestorableChunk>> groupByRegion(
            Path worldRoot,
            List<RestorableChunk> chunks
    ) {
        Map<RegionTarget, List<RestorableChunk>> grouped = new LinkedHashMap<>();
        for (RestorableChunk chunk : chunks) {
            int regionX = Math.floorDiv(chunk.chunk().x(), 32);
            int regionZ = Math.floorDiv(chunk.chunk().z(), 32);
            Path regionDir = this.regionDir(worldRoot, chunk.dimensionId());
            RegionTarget target = new RegionTarget(
                    chunk.dimensionId(),
                    this.dimensionKey(chunk.dimensionId()),
                    regionDir,
                    regionDir.resolve("r." + regionX + "." + regionZ + ".mca")
            );
            grouped.computeIfAbsent(target, ignored -> new ArrayList<>()).add(chunk);
        }
        return grouped;
    }

    private byte[] readBackupChunk(Path file) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() > MAX_CHUNK_NBT_BYTES - read) {
                    throw new IOException("Lumi backup chunk exceeds " + MAX_CHUNK_NBT_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private ChunkPoint parseChunkFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        Matcher matcher = CHUNK_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new ChunkPoint(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String dimensionFolder(String dimensionId) {
        return StoragePathPolicy.safeFolderName(dimensionId.replace(':', '_').replace('/', '_'));
    }

    private ResourceKey<Level> dimensionKey(String dimensionId) {
        Identifier identifier = Identifier.tryParse(dimensionId);
        if (identifier == null) {
            identifier = Level.OVERWORLD.identifier();
        }
        return ResourceKey.create(Registries.DIMENSION, identifier);
    }

    private Path regionDir(Path worldRoot, String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> worldRoot.resolve("region");
            case "minecraft:the_nether" -> worldRoot.resolve("DIM-1").resolve("region");
            case "minecraft:the_end" -> worldRoot.resolve("DIM1").resolve("region");
            default -> {
                String[] parts = dimensionId.split(":", 2);
                String namespace = parts.length == 2 ? parts[0] : "minecraft";
                String path = parts.length == 2 ? parts[1] : parts[0];
                yield worldRoot.resolve("dimensions").resolve(namespace).resolve(path).resolve("region");
            }
        };
    }

    private record RestorableChunk(String dimensionId, ChunkPoint chunk, Path file) {
    }

    private record RegionTarget(
            String dimensionId,
            ResourceKey<Level> dimensionKey,
            Path regionDir,
            Path regionFile
    ) {
    }

    public record RestoreResult(int restoredChunks, int availableChunks) {
    }

}
