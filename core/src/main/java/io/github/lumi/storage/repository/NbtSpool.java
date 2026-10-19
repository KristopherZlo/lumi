package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Worker-only temporary pages; durable action payloads still contain the original NBT bytes. */
public final class NbtSpool implements AutoCloseable {
    private static final int MAX_PAGE_BYTES = 16 << 20;
    private final Path directory;
    private final int threshold;
    private final long capacity;
    private final java.util.Map<Path, WeakReference<CanonicalNbt>> pages = new LinkedHashMap<>();
    private long bytes;
    private boolean closed;

    public NbtSpool(Path repository, int threshold, long capacity) throws IOException {
        if (threshold < 1 || capacity < 1) throw new IllegalArgumentException("Invalid NBT spool budget");
        this.threshold = threshold;
        this.capacity = capacity;
        directory = repository.resolve("actions/spool").resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
    }

    public synchronized CanonicalNbt store(CanonicalNbt value) throws IOException {
        if (closed) throw new IOException("NBT spool is closed");
        if (value.residentByteSize() < threshold) return value;
        if (value.byteSize() > MAX_PAGE_BYTES) throw new IOException("NBT page exceeds its byte limit");
        if (pages.size() % 128 == 0 || pages.size() >= 16_384) pruneReleasedPages();
        if (pages.size() >= 16_384) throw new IOException("NBT spool page budget exhausted");
        Path file = directory.resolve(UUID.randomUUID() + ".nbt.gz");
        try {
            // Temporary pages need no force: publication embeds and forces their original bytes.
            try (var output = new GZIPOutputStream(Files.newOutputStream(file))) { output.write(value.bytes()); }
            long length = Files.size(file);
            if (length > capacity - bytes) pruneReleasedPages();
            if (length > capacity - bytes) throw new IOException("NBT spool disk budget exhausted");
            bytes += length;
            int size = value.byteSize();
            var page = value.stored(() -> read(file, size));
            pages.put(file, new WeakReference<>(page));
            return page;
        } catch (IOException | RuntimeException failed) {
            try { Files.deleteIfExists(file); }
            catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
    }

    private void pruneReleasedPages() throws IOException {
        var retained = pages.entrySet().iterator();
        while (retained.hasNext()) {
            var entry = retained.next();
            if (entry.getValue().get() != null) continue;
            long size = Files.size(entry.getKey());
            Files.delete(entry.getKey());
            bytes -= size;
            retained.remove();
        }
    }

    public SectionBlob store(SectionBlob section) throws IOException {
        var nbt = new LinkedHashMap<Integer, CanonicalNbt>();
        for (var entry : section.blockEntities().entrySet()) nbt.put(entry.getKey(), store(entry.getValue()));
        return new SectionBlob(section.blockStates(), nbt, section.dataVersion(), section.actionBiomes());
    }

    private byte[] read(Path file, int size) throws IOException {
        try (var input = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] result = input.readNBytes(size + 1);
            if (result.length != size) throw new IOException("Invalid NBT page length");
            return result;
        }
    }

    /** Call only after action publication and all world durability readers have drained. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(directory);
        closed = true;
    }
}
