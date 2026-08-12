package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ObjectId;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;

/** Immutable multi-object container with a separately published lookup index. */
final class ObjectPack {
    private static final int PACK_MAGIC = 0x4C555032;
    private static final int PACK_HEADER_BYTES = 8;
    private static final int ENTRY_HEADER_BYTES = 40;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;
    private static final int MAX_COMPRESSED_BYTES = MAX_PAYLOAD_BYTES + 1024 * 1024;
    private static final LZ4Factory LZ4 = LZ4Factory.safeInstance();

    private ObjectPack() {
    }

    static Writer writer(Path directory) throws IOException {
        return new Writer(directory);
    }

    static Map<ObjectId, PackedObject> load(Path directory) throws IOException {
        return ObjectPackIndex.load(directory);
    }

    private static byte[] read(
            FileChannel channel, PackedObject entry, boolean requirePublished)
            throws IOException {
        validateHeader(channel, entry.pack(), requirePublished);
        return readEntry(channel, entry);
    }

    private static void validateHeader(
            FileChannel channel, Path pack, boolean requirePublished)
            throws IOException {
        ByteBuffer packHeader = ByteBuffer.allocate(PACK_HEADER_BYTES);
        readFully(channel, packHeader, 0);
        packHeader.flip();
        int count = packHeader.getInt() == PACK_MAGIC ? packHeader.getInt() : -1;
        if (count < 0 || (requirePublished && count == 0)) {
            throw corrupt(pack, "invalid pack header");
        }
    }

    private static byte[] readEntry(FileChannel channel, PackedObject entry)
            throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ENTRY_HEADER_BYTES);
        readFully(channel, header, entry.offset());
        header.flip();
        byte[] hash = new byte[32];
        header.get(hash);
        ObjectId stored = new ObjectId(HexFormat.of().formatHex(hash));
        int rawLength = header.getInt();
        int compressedLength = header.getInt();
        if (!stored.equals(entry.id())
                || rawLength != entry.rawLength()
                || compressedLength != entry.compressedLength()
                || rawLength < 0 || rawLength > MAX_PAYLOAD_BYTES
                || compressedLength < 0 || compressedLength > MAX_COMPRESSED_BYTES) {
            throw corrupt(entry.pack(), "invalid object entry");
        }
        ByteBuffer compressed = ByteBuffer.allocate(compressedLength);
        readFully(channel, compressed, entry.offset() + ENTRY_HEADER_BYTES);
        byte[] payload = new byte[rawLength];
        try {
            int restored = LZ4.safeDecompressor().decompress(
                    compressed.array(), 0, compressedLength,
                    payload, 0, rawLength);
            if (restored != rawLength) {
                throw corrupt(entry.pack(), "unexpected uncompressed length");
            }
        } catch (LZ4Exception invalid) {
            throw new CorruptObjectException(
                    "Corrupt packed object " + entry.id(), invalid);
        }
        if (!ObjectId.hash(payload).equals(entry.id())) {
            throw corrupt(entry.pack(), "content hash mismatch");
        }
        return payload;
    }

    record Published(Map<ObjectId, PackedObject> entries) {
        Published {
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /** Reuses a bounded set of immutable pack channels for one read session. */
    static final class Reader implements Closeable {
        private static final int MAX_OPEN_PACKS = 32;
        private final LinkedHashMap<Path, FileChannel> channels =
                new LinkedHashMap<>(MAX_OPEN_PACKS, 0.75F, true);

        byte[] read(PackedObject entry) throws IOException {
            Objects.requireNonNull(entry, "entry");
            FileChannel channel = channels.get(entry.pack());
            if (channel == null) {
                channel = open(entry.pack());
            }
            return ObjectPack.readEntry(channel, entry);
        }

        private FileChannel open(Path pack) throws IOException {
            FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ);
            try {
                validateHeader(channel, pack, true);
            } catch (IOException failed) {
                try {
                    channel.close();
                } catch (IOException closeFailed) {
                    failed.addSuppressed(closeFailed);
                }
                throw failed;
            }
            channels.put(pack, channel);
            if (channels.size() > MAX_OPEN_PACKS) {
                var eldest = channels.entrySet().iterator();
                FileChannel evicted = eldest.next().getValue();
                eldest.remove();
                evicted.close();
            }
            return channel;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FileChannel channel : channels.values()) {
                try {
                    channel.close();
                } catch (IOException failed) {
                    if (failure == null) {
                        failure = failed;
                    } else {
                        failure.addSuppressed(failed);
                    }
                }
            }
            channels.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class Writer implements AutoCloseable {
        private final Path directory;
        private final Path temporary;
        private final FileChannel channel;
        private final Map<ObjectId, PackedObject> entries = new LinkedHashMap<>();
        private boolean published;
        private boolean closed;

        private Writer(Path directory) throws IOException {
            this.directory = Objects.requireNonNull(directory, "directory");
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".pack-", ".tmp");
            channel = FileChannel.open(temporary, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            writeFully(channel, ByteBuffer.allocate(PACK_HEADER_BYTES)
                    .putInt(PACK_MAGIC).putInt(0).flip());
        }

        ObjectId write(byte[] payload) throws IOException {
            requireOpen();
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IOException("Object payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
            }
            ObjectId id = ObjectId.hash(payload);
            PackedObject existing = entries.get(id);
            if (existing != null) {
                if (!Arrays.equals(payload, ObjectPack.read(channel, existing, false))) {
                    throw corrupt(temporary, "SHA-256 collision");
                }
                return id;
            }
            if (entries.size() == MAX_OBJECTS) {
                throw new IOException("Object pack exceeds " + MAX_OBJECTS + " entries");
            }
            byte[] compressed = compress(payload);
            long offset = channel.position();
            ByteBuffer header = ByteBuffer.allocate(ENTRY_HEADER_BYTES);
            header.put(HexFormat.of().parseHex(id.hex()));
            header.putInt(payload.length);
            header.putInt(compressed.length);
            header.flip();
            writeFully(channel, header);
            writeFully(channel, ByteBuffer.wrap(compressed));
            entries.put(id, new PackedObject(
                    id, temporary, offset, payload.length, compressed.length));
            return id;
        }

        byte[] read(ObjectId id) throws IOException {
            requireOpen();
            PackedObject entry = entries.get(Objects.requireNonNull(id, "id"));
            return entry == null ? null : ObjectPack.read(channel, entry, false);
        }

        Published publish() throws IOException {
            requireOpen();
            if (entries.isEmpty()) {
                close();
                published = true;
                return new Published(Map.of());
            }
            writeInt(channel, 4, entries.size());
            channel.force(true);
            channel.close();
            closed = true;

            String stem = UUID.randomUUID().toString();
            Path pack = directory.resolve(stem + ".pack");
            moveAtomically(temporary, pack);
            Map<ObjectId, PackedObject> finalEntries = new LinkedHashMap<>();
            entries.forEach((id, entry) -> finalEntries.put(id, new PackedObject(
                    id, pack, entry.offset(), entry.rawLength(), entry.compressedLength())));
            try (FileChannel reopened = FileChannel.open(pack, StandardOpenOption.READ)) {
                for (PackedObject entry : finalEntries.values()) {
                    ObjectPack.read(reopened, entry, true);
                }
            }

            Path index = directory.resolve(stem + ".idx");
            ObjectPackIndex.write(index, finalEntries);
            Map<ObjectId, PackedObject> reopened = ObjectPackIndex.read(index);
            if (!reopened.keySet().equals(finalEntries.keySet())) {
                throw corrupt(index, "index verification mismatch");
            }
            published = true;
            return new Published(reopened);
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                channel.close();
                closed = true;
            }
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }

        private void requireOpen() {
            if (closed || published) {
                throw new IllegalStateException("Object pack writer is closed");
            }
        }
    }

    private static byte[] compress(byte[] payload) {
        var compressor = LZ4.fastCompressor();
        byte[] buffer = new byte[compressor.maxCompressedLength(payload.length)];
        int length = compressor.compress(
                payload, 0, payload.length, buffer, 0, buffer.length);
        return Arrays.copyOf(buffer, length);
    }

    private static void writeInt(FileChannel channel, long offset, int value)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).putInt(value);
        buffer.flip();
        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset)
            throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset);
            if (read < 0) {
                throw new IOException("Truncated object pack");
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

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Object packs require atomic moves: " + target, unsupported);
        }
    }

    private static CorruptObjectException corrupt(Path path, String reason) {
        return new CorruptObjectException("Corrupt object pack " + path + ": " + reason);
    }
}
