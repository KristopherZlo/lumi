package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Optional;

/** Disposable traversal stack; completed work survives in HistoryConversionMap. */
public final class HistoryConversionStack implements AutoCloseable {
    private final Path file;
    private final FileChannel channel;
    private long depth;

    public HistoryConversionStack(Path targetGeneration) throws IOException {
        Path directory = targetGeneration.resolve("conversion-work");
        Files.createDirectories(directory);
        file = Files.createTempFile(directory, "commits-", ".stack");
        channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    public void push(CommitId id) throws IOException {
        var bytes = ByteBuffer.wrap(HexFormat.of().parseHex(id.hex()));
        channel.position(Math.multiplyExact(depth, 32));
        while (bytes.hasRemaining()) channel.write(bytes);
        depth++;
    }

    public Optional<CommitId> peek() throws IOException {
        if (depth == 0) return Optional.empty();
        var bytes = ByteBuffer.allocate(32);
        channel.position(Math.multiplyExact(depth - 1, 32));
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) == -1) throw new IOException("Truncated history conversion stack");
        }
        return Optional.of(new CommitId(new ObjectId(HexFormat.of().formatHex(bytes.array()))));
    }

    public void pop() {
        if (depth == 0) throw new IllegalStateException("Empty history conversion stack");
        depth--;
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
