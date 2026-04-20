package io.github.luma.storage.repository;

import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.GsonProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Reads and writes the shared world-origin manifest for one save.
 */
public final class WorldOriginRepository {

    public Optional<WorldOriginInfo> load(MinecraftServer server) throws IOException {
        Path file = this.file(server);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        return Optional.of(GsonProvider.gson().fromJson(Files.readString(file), WorldOriginInfo.class));
    }

    public WorldOriginInfo ensure(MinecraftServer server) throws IOException {
        Instant now = Instant.now();
        WorldOriginInfo existing = this.load(server).orElse(null);
        Map<String, WorldOriginInfo.DimensionOrigin> dimensions = new LinkedHashMap<>();
        if (existing != null && existing.dimensions() != null) {
            dimensions.putAll(existing.dimensions());
        }

        for (ServerLevel level : server.getAllLevels()) {
            dimensions.put(level.dimension().identifier().toString(), this.describeDimension(level));
        }

        var currentVersion = SharedConstants.getCurrentVersion();
        WorldOptions options = server.getWorldData().worldGenOptions();
        WorldOriginInfo info = new WorldOriginInfo(
                server.getWorldData().getLevelName(),
                currentVersion.name(),
                currentVersion.dataVersion().version(),
                options.seed(),
                Map.copyOf(dimensions),
                existing == null ? now : existing.createdAt(),
                now
        );
        this.save(server, info);
        return info;
    }

    public void save(MinecraftServer server, WorldOriginInfo info) throws IOException {
        Path root = this.root(server);
        Files.createDirectories(root);
        StorageIo.writeAtomically(this.file(server), output -> output.write(
                GsonProvider.gson().toJson(info).getBytes(StandardCharsets.UTF_8)
        ));
    }

    private WorldOriginInfo.DimensionOrigin describeDimension(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        String generatorType = generator.getTypeNameForDataFixer()
                .<String>map(Object::toString)
                .orElse(generator.getClass().getName());
        String biomeSourceType = generator.getBiomeSource().getClass().getName();
        return new WorldOriginInfo.DimensionOrigin(
                level.dimension().identifier().toString(),
                generatorType,
                biomeSourceType,
                generator.getSeaLevel()
        );
    }

    private Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("lumi");
    }

    private Path file(MinecraftServer server) {
        return this.root(server).resolve("world-origin.json");
    }
}
