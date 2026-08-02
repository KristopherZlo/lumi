package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Opt-in process-crash cutpoints used only by the release recovery matrix. */
final class RestoreCrashInjection {
    static final String PHASE_PROPERTY = "lumi.gametest.restoreCrashPhase";
    static final String MARKER_PROPERTY = "lumi.gametest.restoreCrashMarker";
    static final int EXIT_CODE = 86;

    private RestoreCrashInjection() { }

    static void hit(Cutpoint cutpoint) {
        String selected = System.getProperty(PHASE_PROPERTY, "");
        if (!cutpoint.name().toLowerCase(Locale.ROOT).equals(selected)) {
            return;
        }
        Path marker = Path.of(System.getProperty(MARKER_PROPERTY, ""));
        if (marker.toString().isBlank()) {
            throw new IllegalStateException("Restore crash marker is missing");
        }
        try {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            try (FileChannel file = FileChannel.open(marker,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                file.write(ByteBuffer.wrap(selected.getBytes(StandardCharsets.UTF_8)));
                file.force(true);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist Restore crash marker", failed);
        }
        Runtime.getRuntime().halt(EXIT_CODE);
    }

    enum Cutpoint { WRITE, BARRIER, FORCE, VERIFY }
}
