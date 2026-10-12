package io.github.lumi.gametest;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Armed only after the producer fixture is durable. Never included in the mod jar. */
public final class LumiPersistenceCrashProbe {
    private static volatile boolean armed;
    private LumiPersistenceCrashProbe() { }

    static void arm() {
        if (!System.getProperty("lumi.persistence.mode", "").equals("produce")) {
            throw new IllegalStateException("Only crash producers can arm persistence cuts");
        }
        armed = true;
    }

    static Path metadataPath() {
        return FabricLoader.getInstance().getGameDir().resolve("lumi-persistence.properties");
    }

    static Properties readMetadata() throws IOException {
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(metadataPath())) { metadata.load(input); }
        return metadata;
    }

    static synchronized void writeMetadata(Properties metadata) throws IOException {
        try (FileChannel file = FileChannel.open(metadataPath(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            metadata.store(Channels.newOutputStream(file), null);
            file.force(true);
        }
    }

    public static void commitWritten(Commit commit, CommitId id) {
        if (!armed || !commit.kind().name().equalsIgnoreCase(
                System.getProperty("lumi.persistence.kind"))) return;
        try {
            Properties metadata = readMetadata();
            metadata.setProperty("candidate", id.hex());
            writeMetadata(metadata);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot record crash candidate", failed);
        }
        hit("commit-written");
    }

    public static void refForced(Path temporary) {
        if (temporary.getParent().endsWith(Path.of("refs", "heads"))) hit("ref-forced");
    }

    public static synchronized void hit(String phase) {
        if (!armed || !phase.equals(System.getProperty("lumi.persistence.phase"))) return;
        try {
            Path marker = Path.of(System.getProperty("lumi.persistence.marker"));
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Properties reached = new Properties();
            reached.setProperty("kind", System.getProperty("lumi.persistence.kind"));
            reached.setProperty("phase", phase);
            try (FileChannel file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                reached.store(Channels.newOutputStream(file), null);
                file.force(true);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist process-crash marker", failed);
        }
        Runtime.getRuntime().halt(86);
    }
}
