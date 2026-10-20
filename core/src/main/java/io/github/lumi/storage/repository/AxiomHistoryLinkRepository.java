package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Verifies native entry positions against their serialized bytes without changing Axiom files. */
public final class AxiomHistoryLinkRepository {
    private static final int MAGIC = 0x4c414c31, MAX_LINKS = 16_384, ROW_BYTES = 68;
    private final Path file;

    public record Link(UUID epoch, UUID entry, ObjectId fingerprint) {
        public Link { Objects.requireNonNull(epoch); Objects.requireNonNull(entry); Objects.requireNonNull(fingerprint); }
    }

    public AxiomHistoryLinkRepository(Path nativeHistory) { file = nativeHistory.resolve("lumi-links.bin"); }

    public synchronized Map<Integer, Link> read() throws IOException {
        if (!Files.exists(file)) return Map.of();
        byte[] bytes = RepositoryFileReader.read(file, 8 + MAX_LINKS * ROW_BYTES);
        if (bytes.length < 8) throw new IOException("Truncated native history links");
        var input = ByteBuffer.wrap(bytes);
        int magic = input.getInt(), count = input.getInt();
        if (magic != MAGIC || count < 0 || count > MAX_LINKS || input.remaining() != count * ROW_BYTES)
            throw new IOException("Invalid native history links");
        var result = new TreeMap<Integer, Link>();
        int previous = -1;
        for (int row = 0; row < count; row++) {
            int position = input.getInt();
            if (position <= previous) throw new IOException("Invalid native entry position");
            previous = position;
            UUID epoch = uuid(input), entry = uuid(input);
            byte[] hash = new byte[32]; input.get(hash);
            result.put(position, new Link(epoch, entry, new ObjectId(HexFormat.of().formatHex(hash))));
        }
        return Map.copyOf(result);
    }

    /** A genuine native push abandons only its previous Redo continuation. */
    public synchronized void put(int position, Link link) throws IOException {
        if (position < 0) throw new IllegalArgumentException("Invalid native entry position");
        Objects.requireNonNull(link);
        var retained = new TreeMap<>(read());
        retained.tailMap(position).clear();
        retained.put(position, link);
        while (retained.size() > MAX_LINKS) retained.pollFirstEntry();
        var output = ByteBuffer.allocate(8 + retained.size() * ROW_BYTES).putInt(MAGIC).putInt(retained.size());
        retained.forEach((index, value) -> {
            output.putInt(index);
            uuid(output, value.epoch()); uuid(output, value.entry());
            output.put(HexFormat.of().parseHex(value.fingerprint().hex()));
        });
        AtomicFileWriter.replace(file, output.array());
    }

    private static UUID uuid(ByteBuffer input) { return new UUID(input.getLong(), input.getLong()); }
    private static void uuid(ByteBuffer output, UUID id) { output.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
}
