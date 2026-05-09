package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

public final class WorldInitialBackupIdentityReader {

    private final WorldOriginRepository originRepository;

    public WorldInitialBackupIdentityReader() {
        this(new WorldOriginRepository());
    }

    WorldInitialBackupIdentityReader(WorldOriginRepository originRepository) {
        this.originRepository = originRepository;
    }

    public WorldInitialBackupIdentity read(Path worldRoot, String fallbackLevelName) throws IOException {
        if (worldRoot == null) {
            return new WorldInitialBackupIdentity(fallbackLevelName, 0L);
        }

        WorldOriginInfo origin = this.originRepository.load(worldRoot).orElse(null);
        if (origin != null) {
            return new WorldInitialBackupIdentity(origin.levelName(), origin.seed());
        }

        Path levelDat = worldRoot.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return new WorldInitialBackupIdentity(fallbackLevelName, 0L);
        }

        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(levelDat)))) {
            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            CompoundTag data = root == null
                    ? new CompoundTag()
                    : root.getCompound("Data").orElse(root);
            return new WorldInitialBackupIdentity(
                    data.getString("LevelName").orElse(fallbackLevelName),
                    this.seed(data)
            );
        }
    }

    private long seed(CompoundTag data) {
        if (data == null) {
            return 0L;
        }
        return data.getCompound("WorldGenSettings")
                .map(settings -> settings.getLongOr("seed", data.getLongOr("RandomSeed", 0L)))
                .orElseGet(() -> data.getLongOr("RandomSeed", 0L));
    }
}
