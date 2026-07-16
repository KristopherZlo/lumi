package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;

public final class ObjectStore {
    private static final int MAGIC = 0x4C554F32;
    private static final int HEADER_BYTES = 12;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

    private final Path objectsDirectory;
    private final Path packsDirectory;
    private final LZ4Factory lz4 = LZ4Factory.fastestInstance();
    private Map<ObjectId, PackedObject> packedObjects;

    public ObjectStore(Path objectsDirectory) {
        this.objectsDirectory = Objects.requireNonNull(objectsDirectory, "objectsDirectory");
        packsDirectory = objectsDirectory.resolve("packs");
    }

    public ObjectId write(byte[] canonicalPayload) throws IOException {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        if (canonicalPayload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Object payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }

        ObjectId id = ObjectId.hash(canonicalPayload);
        Path target = pathFor(id);
        if (Files.exists(target) || packedObject(id) != null) {
            verifyExisting(id, canonicalPayload);
            return id;
        }

        Files.createDirectories(target.getParent());
        byte[] compressed = compress(canonicalPayload);
        Path temporary = Files.createTempFile(target.getParent(), ".object-", ".tmp");
        try {
            writeDurably(temporary, canonicalPayload.length, compressed);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException racedWriter) {
                Files.deleteIfExists(temporary);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Object store requires atomic moves: " + target, unsupported);
            }
            verifyExisting(id, canonicalPayload);
            return id;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] read(ObjectId id) throws IOException {
        Objects.requireNonNull(id, "id");
        Path path = pathFor(id);
        if (!Files.exists(path)) {
            PackedObject packed = refreshedPackedObject(id);
            if (packed == null) {
                throw new java.nio.file.NoSuchFileException(path.toString());
            }
            return ObjectPack.read(packed);
        }
        return readLoose(id, path);
    }

    private byte[] readLoose(ObjectId id, Path path) throws IOException {
        long fileSize = Files.size(path);
        if (fileSize < HEADER_BYTES || fileSize > HEADER_BYTES + (long) MAX_PAYLOAD_BYTES + 1024 * 1024) {
            throw corrupt(id, "invalid file size");
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(channel, header, id);
            header.flip();
            int magic = header.getInt();
            int rawLength = header.getInt();
            int compressedLength = header.getInt();
            if (magic != MAGIC || rawLength < 0 || rawLength > MAX_PAYLOAD_BYTES
                    || compressedLength < 0 || fileSize != HEADER_BYTES + (long) compressedLength) {
                throw corrupt(id, "invalid header");
            }

            ByteBuffer compressed = ByteBuffer.allocate(compressedLength);
            readFully(channel, compressed, id);
            byte[] payload = new byte[rawLength];
            try {
                int restored = lz4.safeDecompressor()
                        .decompress(compressed.array(), 0, compressedLength, payload, 0, rawLength);
                if (restored != rawLength) {
                    throw corrupt(id, "unexpected uncompressed length");
                }
            } catch (LZ4Exception invalidData) {
                throw new CorruptObjectException("Corrupt compressed object " + id, invalidData);
            }
            if (!ObjectId.hash(payload).equals(id)) {
                throw corrupt(id, "content hash mismatch");
            }
            return payload;
        }
    }

    public Set<ObjectId> listIds() throws IOException {
        Set<ObjectId> ids = new HashSet<>(refreshPackedObjects().keySet());
        if (Files.exists(objectsDirectory)) {
            try (var files = Files.walk(objectsDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".lz4")).toList()) {
                    Path relative = objectsDirectory.relativize(file);
                    String directory = relative.getName(0).toString();
                    String filename = relative.getFileName().toString();
                    if (relative.getNameCount() != 2 || directory.length() != 2
                            || filename.length() != 66) {
                        throw new IOException("Invalid object path: " + file);
                    }
                    ids.add(new ObjectId(directory + filename.substring(0, 62)));
                }
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid object filename", invalid);
            }
        }
        return Set.copyOf(ids);
    }

    public java.time.Instant modifiedAt(ObjectId id) throws IOException {
        Path loose = pathFor(id);
        if (Files.exists(loose)) {
            return Files.getLastModifiedTime(loose).toInstant();
        }
        PackedObject packed = refreshedPackedObject(id);
        if (packed == null) {
            throw new java.nio.file.NoSuchFileException(loose.toString());
        }
        return Files.getLastModifiedTime(packed.pack()).toInstant();
    }

    public void delete(ObjectId id) throws IOException {
        deleteAll(Set.of(id));
    }

    public synchronized int deleteAll(Set<ObjectId> candidates) throws IOException {
        Objects.requireNonNull(candidates, "candidates");
        refreshPackedObjects();
        int deleted = 0;
        for (ObjectId id : candidates) {
            if (Files.deleteIfExists(pathFor(id))) {
                deleted++;
            }
        }

        Map<Path, Set<ObjectId>> contents = new HashMap<>();
        packedObjects().forEach((id, entry) ->
                contents.computeIfAbsent(entry.pack(), ignored -> new HashSet<>()).add(id));
        Map<ObjectId, PackedObject> retained = new HashMap<>(packedObjects());
        for (var pack : contents.entrySet()) {
            if (!candidates.containsAll(pack.getValue())) {
                continue;
            }
            Files.deleteIfExists(indexFor(pack.getKey()));
            Files.deleteIfExists(pack.getKey());
            pack.getValue().forEach(retained::remove);
            deleted += pack.getValue().size();
        }
        packedObjects = Map.copyOf(retained);
        return deleted;
    }

    public synchronized void deleteOrphanPacksBefore(java.time.Instant cutoff)
            throws IOException {
        Objects.requireNonNull(cutoff, "cutoff");
        refreshPackedObjects();
        if (!Files.exists(packsDirectory)) {
            return;
        }
        Set<Path> indexed = new HashSet<>();
        packedObjects().values().forEach(entry -> indexed.add(entry.pack()));
        try (var files = Files.list(packsDirectory)) {
            for (Path pack : files
                    .filter(path -> path.getFileName().toString().endsWith(".pack"))
                    .filter(path -> !indexed.contains(path))
                    .toList()) {
                if (Files.getLastModifiedTime(pack).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(pack);
                }
            }
        }
    }

    public WriteBatch beginBatch() throws IOException {
        return new WriteBatch(ObjectPack.writer(packsDirectory));
    }

    private byte[] compress(byte[] payload) {
        var compressor = lz4.fastCompressor();
        byte[] buffer = new byte[compressor.maxCompressedLength(payload.length)];
        int length = compressor.compress(payload, 0, payload.length, buffer, 0, buffer.length);
        return Arrays.copyOf(buffer, length);
    }

    private void verifyExisting(ObjectId id, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, read(id))) {
            throw corrupt(id, "SHA-256 collision");
        }
    }

    private void writeDurably(Path path, int rawLength, byte[] compressed) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                    .putInt(MAGIC)
                    .putInt(rawLength)
                    .putInt(compressed.length);
            header.flip();
            writeFully(channel, header);
            writeFully(channel, ByteBuffer.wrap(compressed));
            channel.force(true);
        }
    }

    private Path pathFor(ObjectId id) {
        String hex = id.hex();
        return objectsDirectory.resolve(hex.substring(0, 2)).resolve(hex.substring(2) + ".lz4");
    }

    private static Path indexFor(Path pack) {
        String name = pack.getFileName().toString();
        return pack.resolveSibling(name.substring(0, name.length() - 5) + ".idx");
    }

    private synchronized PackedObject packedObject(ObjectId id) throws IOException {
        return packedObjects().get(id);
    }

    private synchronized PackedObject refreshedPackedObject(ObjectId id) throws IOException {
        boolean catalogWasLoaded = packedObjects != null;
        PackedObject packed = packedObjects().get(id);
        if (packed != null || !catalogWasLoaded) {
            return packed;
        }
        return refreshPackedObjects().get(id);
    }

    private synchronized Map<ObjectId, PackedObject> packedObjects() throws IOException {
        if (packedObjects == null) {
            packedObjects = ObjectPack.load(packsDirectory);
        }
        return packedObjects;
    }

    private synchronized Map<ObjectId, PackedObject> refreshPackedObjects() throws IOException {
        packedObjects = ObjectPack.load(packsDirectory);
        return packedObjects;
    }

    private synchronized void register(ObjectPack.Published published) {
        if (published.entries().isEmpty()) {
            return;
        }
        Map<ObjectId, PackedObject> updated = new HashMap<>(
                packedObjects == null ? Map.of() : packedObjects);
        published.entries().forEach(updated::putIfAbsent);
        packedObjects = Map.copyOf(updated);
    }

    private static void readFully(FileChannel channel, ByteBuffer target, ObjectId id) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw corrupt(id, "truncated file");
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    private static CorruptObjectException corrupt(ObjectId id, String reason) {
        return new CorruptObjectException("Corrupt object " + id + ": " + reason);
    }

    public final class WriteBatch implements AutoCloseable {
        private final ObjectPack.Writer writer;
        private boolean published;

        private WriteBatch(ObjectPack.Writer writer) {
            this.writer = writer;
        }

        public ObjectId write(byte[] canonicalPayload) throws IOException {
            Objects.requireNonNull(canonicalPayload, "canonicalPayload");
            ObjectId id = ObjectId.hash(canonicalPayload);
            if (Files.exists(pathFor(id)) || packedObject(id) != null) {
                verifyExisting(id, canonicalPayload);
                return id;
            }
            return writer.write(canonicalPayload);
        }

        public void publish() throws IOException {
            if (published) {
                throw new IllegalStateException("Object batch is already published");
            }
            ObjectPack.Published completed = writer.publish();
            register(completed);
            published = true;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
