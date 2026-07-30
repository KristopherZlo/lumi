package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Compares captured dirty sections with saved HEAD without reading live Minecraft state. */
public final class PendingChangeStatisticsService {
    private static final int REGION_SIZE = 32;
    private static final Set<String> AIR = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;

    public PendingChangeStatisticsService(
            WorldObjectRepository objects,
            CommitRepository commits,
            OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    public Result calculate(
            CommitId head,
            Map<SectionKey, SectionBlob> current,
            List<Zone> zones) throws IOException {
        return calculate(head, current, zones, () -> false);
    }

    public Result calculate(
            CommitId head,
            Map<SectionKey, SectionBlob> current,
            List<Zone> zones,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(head, "head");
        Map<SectionKey, SectionBlob> captured = Map.copyOf(
                Objects.requireNonNull(current, "current"));
        List<Zone> visibleZones = List.copyOf(
                Objects.requireNonNull(zones, "zones"));
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);
        try (var reader = objects.beginReadSession()) {
            DimensionTree saved = reader.readDimension(commits.read(head).tree());
            Map<ObjectId, RegionTree> regions = new HashMap<>();
            Map<ObjectId, ChunkTree> chunks = new HashMap<>();
            PendingChangeStatistics workspace = PendingChangeStatistics.NONE;
            Map<UUID, PendingChangeStatistics> byZone = new HashMap<>();
            for (var entry : captured.entrySet()) {
                checkCancelled(cancelled);
                PendingChangeStatistics section = compare(
                        baseline(saved, entry.getKey(), reader, regions, chunks),
                        entry.getValue(), cancelled);
                workspace = workspace.plus(section);
                for (Zone zone : visibleZones) {
                    if (zone.cells().contains(entry.getKey())) {
                        byZone.merge(
                                zone.id(), section, PendingChangeStatistics::plus);
                    }
                }
            }
            visibleZones.forEach(zone ->
                    byZone.putIfAbsent(zone.id(), PendingChangeStatistics.NONE));
            return new Result(workspace, byZone);
        }
    }

    private SectionBlob baseline(
            DimensionTree tree,
            SectionKey key,
            WorldObjectRepository.ReadSession reader,
            Map<ObjectId, RegionTree> regions,
            Map<ObjectId, ChunkTree> chunks)
            throws IOException {
        int regionX = Math.floorDiv(key.chunkX(), REGION_SIZE);
        int regionZ = Math.floorDiv(key.chunkZ(), REGION_SIZE);
        Optional<ObjectId> section = Optional.empty();
        ObjectId regionId = tree.regions().get(
                new RegionCoordinate(regionX, regionZ));
        if (regionId != null) {
            RegionTree region = regions.get(regionId);
            if (region == null) {
                region = reader.readRegion(regionId);
                regions.put(regionId, region);
            }
            ObjectId chunkId = region.chunks().get(new ChunkInRegion(
                    Math.floorMod(key.chunkX(), REGION_SIZE),
                    Math.floorMod(key.chunkZ(), REGION_SIZE)));
            if (chunkId != null) {
                ChunkTree chunk = chunks.get(chunkId);
                if (chunk == null) {
                    chunk = reader.readChunk(chunkId);
                    chunks.put(chunkId, chunk);
                }
                section = Optional.ofNullable(
                        chunk.sections().get(key.sectionY()));
            }
        }
        ObjectId resolved = section.isPresent()
                ? section.orElseThrow()
                : origins.read(key).orElseThrow(() -> new IOException(
                        "Missing saved section and origin for " + key));
        return reader.readSection(resolved);
    }

    private static PendingChangeStatistics compare(
            SectionBlob before,
            SectionBlob after,
            BooleanSupplier cancelled) throws IOException {
        long added = 0;
        long removed = 0;
        long changed = 0;
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            if ((index & 255) == 0) {
                checkCancelled(cancelled);
            }
            String left = before.blockStates().get(index);
            String right = after.blockStates().get(index);
            if (left.equals(right)
                    && Objects.equals(
                            before.blockEntities().get(index),
                            after.blockEntities().get(index))) {
                continue;
            }
            if (isAir(left) && !isAir(right)) {
                added++;
            } else if (!isAir(left) && isAir(right)) {
                removed++;
            } else {
                changed++;
            }
        }
        return new PendingChangeStatistics(added, removed, changed);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException(
                    "Pending statistics calculation was cancelled");
        }
    }

    private static boolean isAir(String state) throws IOException {
        int properties = state.indexOf('[');
        String id = properties < 0 ? state : state.substring(0, properties);
        if (id.isBlank()) {
            throw new IOException("Invalid persistent block state: " + state);
        }
        return AIR.contains(id);
    }

    public record Result(
            PendingChangeStatistics workspace,
            Map<UUID, PendingChangeStatistics> zones) {
        public Result {
            Objects.requireNonNull(workspace, "workspace");
            zones = Map.copyOf(Objects.requireNonNull(zones, "zones"));
        }
    }
}
