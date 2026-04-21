package io.github.luma.storage.repository;

import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.GsonProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Reads and writes the shared world-origin manifest for one save.
 */
public final class WorldOriginRepository {

    private static final int CURRENT_SCHEMA_VERSION = 2;

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

        WorldOptions options = server.getWorldData().worldGenOptions();
        for (ServerLevel level : server.getAllLevels()) {
            dimensions.putIfAbsent(level.dimension().identifier().toString(), this.describeDimension(level, options.seed()));
        }

        var currentVersion = SharedConstants.getCurrentVersion();
        WorldOriginInfo info = new WorldOriginInfo(
                CURRENT_SCHEMA_VERSION,
                server.getWorldData().getLevelName(),
                currentVersion.name(),
                currentVersion.dataVersion().version(),
                options.seed(),
                existing != null && existing.createdWithLumi(),
                this.nonBlank(existing == null ? "" : existing.datapackFingerprint())
                        ? existing.datapackFingerprint()
                        : this.fingerprintDataPacks(server.getWorldData().getDataConfiguration().dataPacks()),
                Map.copyOf(dimensions),
                existing == null || existing.createdAt() == null ? now : existing.createdAt(),
                now
        );
        this.save(server, info);
        return info;
    }

    public boolean matchesCurrentFingerprints(MinecraftServer server, String dimensionId) throws IOException {
        WorldOriginInfo origin = this.load(server).orElse(null);
        if (origin == null || !this.nonBlank(origin.datapackFingerprint())) {
            return false;
        }
        String currentDataPacks = this.fingerprintDataPacks(server.getWorldData().getDataConfiguration().dataPacks());
        if (!Objects.equals(origin.datapackFingerprint(), currentDataPacks)) {
            return false;
        }
        WorldOriginInfo.DimensionOrigin storedDimension = origin.dimensions() == null ? null : origin.dimensions().get(dimensionId);
        if (storedDimension == null || !this.nonBlank(storedDimension.generatorFingerprint())) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().identifier().toString().equals(dimensionId)) {
                continue;
            }
            return Objects.equals(storedDimension.generatorFingerprint(), this.describeDimension(level, origin.seed()).generatorFingerprint());
        }
        return false;
    }

    public void save(MinecraftServer server, WorldOriginInfo info) throws IOException {
        Path root = this.root(server);
        Files.createDirectories(root);
        StorageIo.writeAtomically(this.file(server), output -> output.write(
                GsonProvider.gson().toJson(info).getBytes(StandardCharsets.UTF_8)
        ));
    }

    private WorldOriginInfo.DimensionOrigin describeDimension(ServerLevel level, long seed) {
        var generator = level.getChunkSource().getGenerator();
        String dimensionId = level.dimension().identifier().toString();
        String generatorType = generator.getTypeNameForDataFixer()
                .<String>map(Object::toString)
                .orElse(generator.getClass().getName());
        String biomeSourceType = generator.getBiomeSource().getClass().getName();
        int seaLevel = generator.getSeaLevel();
        return new WorldOriginInfo.DimensionOrigin(
                dimensionId,
                generatorType,
                biomeSourceType,
                seaLevel,
                this.fingerprint(
                        "generator",
                        List.of(dimensionId, generatorType, biomeSourceType, Integer.toString(seaLevel), Long.toString(seed))
                )
        );
    }

    private String fingerprintDataPacks(DataPackConfig config) {
        List<String> entries = new ArrayList<>();
        for (String enabled : config.getEnabled()) {
            entries.add("enabled:" + enabled);
        }
        for (String disabled : config.getDisabled()) {
            entries.add("disabled:" + disabled);
        }
        entries.sort(String::compareTo);
        return this.fingerprint("datapacks", entries);
    }

    private String fingerprint(String namespace, List<String> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespace.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (String entry : entries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("lumi");
    }

    private Path file(MinecraftServer server) {
        return this.root(server).resolve("world-origin.json");
    }
}
