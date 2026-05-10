package io.github.luma.minecraft.bootstrap;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

public final class WorldInitialBackupService {

    private static final long BACKGROUND_PAUSE_NANOS = 500_000L;
    private static final int PROGRESS_PUBLISH_INTERVAL_CHUNKS = 32;
    private final WorldInitialBackupRepository repository;
    private final RegionChunkScanner regionScanner;
    private final WorldChunkActivityClassifier activityClassifier;
    private final WorldInitialBackupStoragePolicy storagePolicy;

    public WorldInitialBackupService() {
        this(
                new WorldInitialBackupRepository(),
                new RegionChunkScanner(),
                new WorldChunkActivityClassifier(),
                new WorldInitialBackupStoragePolicy()
        );
    }

    WorldInitialBackupService(
            WorldInitialBackupRepository repository,
            RegionChunkScanner regionScanner,
            WorldChunkActivityClassifier activityClassifier,
            WorldInitialBackupStoragePolicy storagePolicy
    ) {
        this.repository = repository;
        this.regionScanner = regionScanner;
        this.activityClassifier = activityClassifier;
        this.storagePolicy = storagePolicy;
    }

    public void backupIfNeeded(MinecraftServer server, WorldOriginInfo origin) throws IOException {
        if (server == null || origin == null) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        this.backupPlansIfNeeded(
                worldRoot,
                origin.levelName(),
                origin.seed(),
                this.serverDimensionPlans(worldRoot, server),
                ProgressListener.NO_OP
        );
    }

    public void backupWorldRootIfNeeded(
            Path worldRoot,
            String levelName,
            long seed,
            ProgressListener progressListener
    ) throws IOException {
        if (worldRoot == null) {
            return;
        }
        this.backupPlansIfNeeded(
                worldRoot,
                levelName,
                seed,
                this.discoveredDimensionPlans(worldRoot),
                progressListener
        );
    }

    private void backupPlansIfNeeded(
            Path worldRoot,
            String levelName,
            long seed,
            List<DimensionRegionPlan> plans,
            ProgressListener progressListener
    ) throws IOException {
        if (worldRoot == null || this.repository.completedForSeed(worldRoot, seed)) {
            return;
        }
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        Instant startedAt = Instant.now();
        Map<String, WorldInitialBackupManifest.DimensionBackupSummary> dimensions = new LinkedHashMap<>();
        ProgressState progress = new ProgressState(this.totalChunkCount(plans), progressListener);
        progress.publish("");
        for (DimensionRegionPlan plan : plans) {
            dimensions.put(plan.dimensionId(), this.backupDimension(worldRoot, plan, progress));
        }

        this.repository.save(worldRoot, new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                levelName,
                seed,
                WorldChunkActivityClassifier.NAME,
                this.storagePolicy.maxCompressedBytes(),
                dimensions,
                startedAt,
                Instant.now()
        ));
        progress.complete();
        LumaMod.LOGGER.info(
                "Completed pre-mod world backup scan for {} dimensions at {}",
                dimensions.size(),
                this.repository.backupRoot(worldRoot)
        );
    }

    private WorldInitialBackupManifest.DimensionBackupSummary backupDimension(
            Path worldRoot,
            DimensionRegionPlan plan,
            ProgressState progress
    ) throws IOException {
        if (plan == null || plan.regionFiles().isEmpty()) {
            return new WorldInitialBackupManifest.DimensionBackupSummary(
                    plan == null ? "" : plan.dimensionId(),
                    0,
                    0,
                    0,
                    0L
            );
        }

        int scanned = 0;
        int backedUp = 0;
        int skippedPristine = 0;
        int skippedVisitedOnly = 0;
        int skippedByBudget = 0;
        long bytes = 0L;
        for (Path regionFile : plan.regionFiles()) {
            List<RegionChunkScanner.RegionChunkRecord> chunks;
            try {
                chunks = this.regionScanner.scan(regionFile);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Skipping unreadable pre-mod backup region file {}", regionFile, exception);
                continue;
            }
            for (RegionChunkScanner.RegionChunkRecord chunk : chunks) {
                scanned += 1;
                WorldChunkActivityClassifier.ChunkBackupDecision decision = this.activityClassifier.classify(chunk.tag());
                boolean written = false;
                long writtenBytes = 0L;
                if (decision == WorldChunkActivityClassifier.ChunkBackupDecision.BACKUP) {
                    WorldInitialBackupRepository.ChunkWriteResult result = this.repository.writeChunk(
                            worldRoot,
                            plan.dimensionId(),
                            chunk.chunk(),
                            chunk.nbtBytes(),
                            this.storagePolicy.remainingBytes(bytes)
                    );
                    if (result.written()) {
                        bytes += result.compressedBytes();
                        backedUp += 1;
                        written = true;
                        writtenBytes = result.compressedBytes();
                    } else {
                        skippedByBudget += 1;
                    }
                } else if (decision == WorldChunkActivityClassifier.ChunkBackupDecision.SKIP_VISITED_ONLY) {
                    skippedVisitedOnly += 1;
                } else {
                    skippedPristine += 1;
                }
                progress.advance(plan.dimensionId(), written, writtenBytes);
                if ((scanned % 64) == 0) {
                    LockSupport.parkNanos(BACKGROUND_PAUSE_NANOS);
                }
            }
        }
        return new WorldInitialBackupManifest.DimensionBackupSummary(
                plan.dimensionId(),
                scanned,
                backedUp,
                skippedPristine,
                skippedVisitedOnly,
                skippedByBudget,
                bytes,
                skippedByBudget > 0
        );
    }

    private List<DimensionRegionPlan> serverDimensionPlans(Path worldRoot, MinecraftServer server) throws IOException {
        List<DimensionRegionPlan> plans = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = level.dimension().identifier().toString();
            plans.add(new DimensionRegionPlan(dimensionId, this.regionFiles(this.regionDir(worldRoot, dimensionId))));
        }
        return List.copyOf(plans);
    }

    private List<DimensionRegionPlan> discoveredDimensionPlans(Path worldRoot) throws IOException {
        List<DimensionRegionPlan> plans = new ArrayList<>();
        plans.add(new DimensionRegionPlan("minecraft:overworld", this.regionFiles(this.regionDir(worldRoot, "minecraft:overworld"))));
        plans.add(new DimensionRegionPlan("minecraft:the_nether", this.regionFiles(this.regionDir(worldRoot, "minecraft:the_nether"))));
        plans.add(new DimensionRegionPlan("minecraft:the_end", this.regionFiles(this.regionDir(worldRoot, "minecraft:the_end"))));

        Path dimensionsRoot = worldRoot == null ? null : worldRoot.resolve("dimensions");
        if (dimensionsRoot != null && Files.isDirectory(dimensionsRoot)) {
            try (var namespaces = Files.list(dimensionsRoot)) {
                for (Path namespace : namespaces.filter(Files::isDirectory).sorted().toList()) {
                    try (var dimensionDirs = Files.list(namespace)) {
                        for (Path dimensionDir : dimensionDirs.filter(Files::isDirectory).sorted().toList()) {
                            Path regionDir = dimensionDir.resolve("region");
                            if (Files.isDirectory(regionDir)) {
                                plans.add(new DimensionRegionPlan(
                                        namespace.getFileName() + ":" + dimensionDir.getFileName(),
                                        this.regionFiles(regionDir)
                                ));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(plans);
    }

    private List<Path> regionFiles(Path regionDir) throws IOException {
        if (!Files.isDirectory(regionDir)) {
            return List.of();
        }
        try (var stream = Files.list(regionDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
        }
    }

    private int totalChunkCount(List<DimensionRegionPlan> plans) throws IOException {
        int total = 0;
        for (DimensionRegionPlan plan : plans == null ? List.<DimensionRegionPlan>of() : plans) {
            for (Path regionFile : plan.regionFiles()) {
                total += this.regionScanner.countChunks(regionFile);
            }
        }
        return Math.max(1, total);
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

    public interface ProgressListener {

        ProgressListener NO_OP = progress -> {
        };

        void onProgress(WorldInitialBackupProgress progress);
    }

    private record DimensionRegionPlan(String dimensionId, List<Path> regionFiles) {

        private DimensionRegionPlan {
            dimensionId = dimensionId == null ? "" : dimensionId;
            regionFiles = regionFiles == null ? List.of() : List.copyOf(regionFiles);
        }
    }

    private static final class ProgressState {

        private final int totalChunks;
        private final ProgressListener listener;
        private int completedChunks;
        private int backedUpChunks;
        private long compressedBytes;

        private ProgressState(int totalChunks, ProgressListener listener) {
            this.totalChunks = Math.max(1, totalChunks);
            this.listener = listener == null ? ProgressListener.NO_OP : listener;
        }

        private void advance(String dimensionId, boolean backedUp, long compressedBytes) {
            this.completedChunks += 1;
            if (backedUp) {
                this.backedUpChunks += 1;
                this.compressedBytes += Math.max(0L, compressedBytes);
            }
            if (this.shouldPublishProgress()) {
                this.publish(dimensionId);
            }
        }

        private void complete() {
            this.completedChunks = this.totalChunks;
            this.publish("");
        }

        private void publish(String dimensionId) {
            this.listener.onProgress(new WorldInitialBackupProgress(
                    Math.min(this.completedChunks, this.totalChunks),
                    this.totalChunks,
                    this.backedUpChunks,
                    this.compressedBytes,
                    dimensionId
            ));
        }

        private boolean shouldPublishProgress() {
            return this.completedChunks % PROGRESS_PUBLISH_INTERVAL_CHUNKS == 0;
        }
    }
}
