package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable lookup table published only after its immutable pack verifies. */
final class ObjectPackIndex {
    private static final int MAGIC = 0x4C554932;
    private static final int ENTRY_BYTES = 48;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;
    private static final int MAX_COMPRESSED_BYTES = MAX_PAYLOAD_BYTES + 1024 * 1024;

    private ObjectPackIndex() {
    }

    static Map<ObjectId, PackedObject> load(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return Map.of();
        }
        Map<ObjectId, PackedObject> entries = new LinkedHashMap<>();
        try (var indexes = Files.list(directory)) {
            for (Path index : indexes
                    .filter(path -> path.getFileName().toString().endsWith(".idx"))
                    .sorted()
                    .toList()) {
                for (var entry : read(index).entrySet()) {
                    entries.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return Map.copyOf(entries);
    }

    static Map<ObjectId, PackedObject> read(Path index) throws IOException {
        String name = index.getFileName().toString();
        Path pack = index.resolveSibling(name.substring(0, name.length() - 4) + ".pack");
        if (!Files.isRegularFile(pack)) {
            throw corrupt(index, "pack is missing");
        }
        long size = Files.size(index);
        try (FileChannel channel = FileChannel.open(index, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(8);
            readFully(channel, header, 0);
            header.flip();
            int magic = header.getInt();
            int count = header.getInt();
            if (magic != MAGIC || count <= 0 || count > MAX_OBJECTS
                    || size != 8L + (long) count * ENTRY_BYTES) {
                throw corrupt(index, "invalid index header");
            }
            Map<ObjectId, PackedObject> entries = new LinkedHashMap<>();
            ByteBuffer encoded = ByteBuffer.allocate(ENTRY_BYTES);
            for (int ordinal = 0; ordinal < count; ordinal++) {
                encoded.clear();
                readFully(channel, encoded, 8L + (long) ordinal * ENTRY_BYTES);
                encoded.flip();
                byte[] hash = new byte[32];
                encoded.get(hash);
                ObjectId id = new ObjectId(HexFormat.of().formatHex(hash));
                PackedObject entry = new PackedObject(
                        id, pack, encoded.getLong(), encoded.getInt(), encoded.getInt());
                if (entry.offset() < 8
                        || entry.rawLength() < 0 || entry.rawLength() > MAX_PAYLOAD_BYTES
                        || entry.compressedLength() < 0
                        || entry.compressedLength() > MAX_COMPRESSED_BYTES
                        || entries.putIfAbsent(id, entry) != null) {
                    throw corrupt(index, "invalid index entry");
                }
            }
            return Map.copyOf(entries);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid packed object ID in " + index, invalid);
        }
    }

    static void write(Path target, Map<ObjectId, PackedObject> entries)
            throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".index-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, ByteBuffer.allocate(8)
                    .putInt(MAGIC).putInt(entries.size()).flip());
            for (PackedObject entry : entries.values().stream()
                    .sorted(Comparator.comparing(value -> value.id().hex())).toList()) {
                ByteBuffer encoded = ByteBuffer.allocate(ENTRY_BYTES);
                encoded.put(HexFormat.of().parseHex(entry.id().hex()));
                encoded.putLong(entry.offset());
                encoded.putInt(entry.rawLength());
                encoded.putInt(entry.compressedLength());
                encoded.flip();
                writeFully(channel, encoded);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Object pack indexes require atomic moves", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset)
            throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset);
            if (read < 0) {
                throw new IOException("Truncated object pack index");
            }
            offset += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source)
            throws IOException {
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    private static CorruptObjectException corrupt(Path path, String reason) {
        return new CorruptObjectException("Corrupt object pack index " + path + ": " + reason);
    }
}
