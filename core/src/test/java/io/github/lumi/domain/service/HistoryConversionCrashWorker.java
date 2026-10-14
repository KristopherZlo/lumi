package io.github.lumi.domain.service;

import io.github.lumi.domain.model.WorldDataVersion;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Separate JVM for real persistence cuts; never shipped in Lumi. */
public final class HistoryConversionCrashWorker {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String phase = args[1];
        new HistoryGenerationConversionService(root).convert(new WorldDataVersion(4786),
                HistoryGenerationConversionServiceTest.adapter(), progress -> {
                    if (!progress.phase().name().equals(phase)) return;
                    try (var file = FileChannel.open(root.resolve("crash-cut.txt"),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        var bytes = ByteBuffer.wrap(phase.getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) file.write(bytes);
                        file.force(true);
                    } catch (Exception failed) { throw new IllegalStateException("Cannot record crash cut", failed); }
                    Runtime.getRuntime().halt(86);
                });
    }
}
