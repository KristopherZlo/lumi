package io.github.lumi.client.preview;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.DecodedSection;
import io.github.lumi.minecraft.world.MinecraftBlockStateDecoder;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.storage.LevelResource;

/** Reads a bounded block-state snapshot from one immutable published commit. */
final class CommitPreviewSnapshotReader {
    static final int MAX_SECTIONS = 256;
    private static final int REGION_SIZE = 32;

    FrozenPreviewBlockGetter read(
            MinecraftServer server,
            BlockAndTintGetter lighting,
            String dimensionId,
            CommitId commitId,
            BlockBox bounds) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(bounds, "bounds");
        Path repository = new DimensionRepositoryLayout(
                server.getWorldPath(LevelResource.ROOT)).resolve(dimensionId);
        var commits = new CommitRepository(repository);
        var objects = new WorldObjectRepository(repository);
        var decoder = new MinecraftBlockStateDecoder(
                server.registryAccess().lookupOrThrow(Registries.BLOCK));
        var dimension = objects.readDimension(commits.read(commitId).tree());
        Map<SectionKey, DecodedSection> selected = new HashMap<>();
        for (var regionEntry : dimension.regions().entrySet()) {
            RegionCoordinate region = regionEntry.getKey();
            int regionX = Math.multiplyExact(region.x(), REGION_SIZE);
            int regionZ = Math.multiplyExact(region.z(), REGION_SIZE);
            var regionTree = objects.readRegion(regionEntry.getValue());
            for (var chunkEntry : regionTree.chunks().entrySet()) {
                int chunkX = Math.addExact(regionX, chunkEntry.getKey().x());
                int chunkZ = Math.addExact(regionZ, chunkEntry.getKey().z());
                var chunk = objects.readChunk(chunkEntry.getValue());
                for (var sectionEntry : chunk.sections().entrySet()) {
                    SectionKey key = new SectionKey(
                            chunkX, sectionEntry.getKey(), chunkZ);
                    if (!bounds.intersects(key)) continue;
                    if (selected.size() == MAX_SECTIONS) {
                        throw new IOException(
                                "Preview exceeds the 256-section render limit");
                    }
                    selected.put(key, decoder.decode(
                            objects.readSection(sectionEntry.getValue())));
                }
            }
        }
        return new FrozenPreviewBlockGetter(lighting, selected);
    }
}
