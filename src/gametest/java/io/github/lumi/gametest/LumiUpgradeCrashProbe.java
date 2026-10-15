package io.github.lumi.gametest;

import io.github.lumi.domain.service.HistoryGenerationConversionService.Phase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Process cuts exist only in the separate GameTest mod. */
public final class LumiUpgradeCrashProbe {
    private LumiUpgradeCrashProbe() { }

    public static void hit(Phase phase) {
        if (!phase.name().equals(System.getProperty("lumi.gametest.upgrade.cut", ""))) return;
        Path marker = Path.of(System.getProperty("lumi.gametest.upgrade.marker")).toAbsolutePath();
        try {
            Files.createDirectories(marker.getParent());
            try (var file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(phase.name().getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist history upgrade crash marker", failed);
        }
        Runtime.getRuntime().halt(86);
    }
}
