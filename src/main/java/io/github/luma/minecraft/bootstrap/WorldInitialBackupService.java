package io.github.luma.minecraft.bootstrap;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

public final class WorldInitialBackupService {

    private static final long BACKGROUND_PAUSE_NANOS = 500_000L;
    private final WorldInitialBackupRepository repository;
    private final RegionChunkScanner regionScanner;
    private final WorldChunkActivityClassifier activityClassifier;

    public WorldInitialBackupService() {
        this(new WorldInitialBackupRepository(), new RegionChunkScanner(), new WorldChunkActivityClassifier());
    }

    WorldInitialBackupService(
            WorldInitialBackupRepository repository,
            RegionChunkScanner regionScanner,
            WorldChunkActivityClassifier activityClassifier
    ) {
        this.repository = repository;
        this.regionScanner = regionScanner;
        this.activityClassifier = activityClassifier;
    }

    public void backupIfNeeded(MinecraftServer server, WorldOriginInfo origin) throws IOException {
        if (server == null || origin == null) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (this.repository.completedForSeed(worldRoot, origin.seed())) {
            return;
        }

        Instant startedAt = Instant.now();
        Map<String, WorldInitialBackupManifest.DimensionBackupSummary> dimensions = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = level.dimension().identifier().toString();
            dimensions.put(dimensionId, this.backupDimension(worldRoot, dimensionId));
        }

        this.repository.save(worldRoot, new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                origin.levelName(),
                origin.seed(),
                WorldChunkActivityClassifier.NAME,
                dimensions,
                startedAt,
                Instant.now()
        ));
        LumaMod.LOGGER.info(
                "Completed pre-mod world backup scan for {} dimensions at {}",
                dimensions.size(),
                this.repository.backupRoot(worldRoot)
        );
    }

    private WorldInitialBackupManifest.DimensionBackupSummary backupDimension(
            Path worldRoot,
            String dimensionId
    ) throws IOException {
        Path regionDir = this.regionDir(worldRoot, dimensionId);
        if (!Files.isDirectory(regionDir)) {
            return new WorldInitialBackupManifest.DimensionBackupSummary(dimensionId, 0, 0, 0, 0L);
        }

        List<Path> regionFiles;
        try (var stream = Files.list(regionDir)) {
            regionFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
        }

        int scanned = 0;
        int backedUp = 0;
        int skipped = 0;
        long bytes = 0L;
        for (Path regionFile : regionFiles) {
            List<RegionChunkScanner.RegionChunkRecord> chunks;
            try {
                chunks = this.regionScanner.scan(regionFile);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Skipping unreadable pre-mod backup region file {}", regionFile, exception);
                continue;
            }
            for (RegionChunkScanner.RegionChunkRecord chunk : chunks) {
                scanned += 1;
                if (this.activityClassifier.shouldBackup(chunk.tag())) {
                    bytes += this.repository.writeChunk(worldRoot, dimensionId, chunk.chunk(), chunk.nbtBytes());
                    backedUp += 1;
                } else {
                    skipped += 1;
                }
                if ((scanned % 64) == 0) {
                    LockSupport.parkNanos(BACKGROUND_PAUSE_NANOS);
                }
            }
        }
        return new WorldInitialBackupManifest.DimensionBackupSummary(dimensionId, scanned, backedUp, skipped, bytes);
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
}
