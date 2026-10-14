package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Objects;

/** Atomic mutable version tags keyed by immutable commit identity. */
public final class VersionTagRepository {
    private static final int MAGIC = 0x4C565432;
    private static final int MAX_TAG_BYTES = VersionTags.MAX_TAG_LENGTH * 4;
    private static final int MAX_FILE_BYTES =
            Integer.BYTES + 32 + 1 + VersionTags.MAX_TAGS * (Short.BYTES + MAX_TAG_BYTES);
    private final Path directory;

    public VersionTagRepository(Path dimensionRepository) {
        directory = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("tags");
    }

    public synchronized VersionTags read(CommitId commit) throws IOException {
        CommitId expected = Objects.requireNonNull(commit, "commit");
        Path file = path(expected);
        if (!Files.exists(file)) {
            return VersionTags.empty();
        }
        long size = Files.size(file);
        if (size < Integer.BYTES + 32 + 1 || size > MAX_FILE_BYTES) {
            throw new IOException("Invalid Lumi version tag file size: " + size);
        }
        return decode(expected, RepositoryFileReader.read(file, MAX_FILE_BYTES));
    }

    public synchronized void replace(CommitId commit, VersionTags tags) throws IOException {
        AtomicFileWriter.replace(
                path(Objects.requireNonNull(commit, "commit")),
                encode(commit, Objects.requireNonNull(tags, "tags")));
    }

    private static byte[] encode(CommitId commit, VersionTags tags) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.write(HexFormat.of().parseHex(commit.hex()));
            output.writeByte(tags.values().size());
            for (String tag : tags.values()) {
                byte[] encoded = tag.getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_TAG_BYTES) {
                    throw new IOException("Version tag UTF-8 payload is too large");
                }
                output.writeShort(encoded.length);
                output.write(encoded);
            }
        }
        return bytes.toByteArray();
    }

    private static VersionTags decode(CommitId expected, byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 version tag file");
            }
            byte[] commitBytes = input.readNBytes(32);
            if (commitBytes.length != 32) {
                throw new IOException("Truncated version tag commit ID");
            }
            CommitId stored = new CommitId(
                    new ObjectId(HexFormat.of().formatHex(commitBytes)));
            if (!stored.equals(expected)) {
                throw new IOException("Version tag filename and payload disagree");
            }
            int count = input.readUnsignedByte();
            if (count > VersionTags.MAX_TAGS) {
                throw new IOException("Invalid version tag count");
            }
            ArrayList<String> tags = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int length = input.readUnsignedShort();
                if (length < 1 || length > MAX_TAG_BYTES) {
                    throw new IOException("Invalid version tag length");
                }
                byte[] encoded = input.readNBytes(length);
                if (encoded.length != length) {
                    throw new IOException("Truncated version tag");
                }
                tags.add(decodeUtf8(encoded));
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in version tag file");
            }
            return new VersionTags(tags);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid version tag file", invalid);
        }
    }

    private static String decodeUtf8(byte[] encoded) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString();
    }

    private Path path(CommitId commit) {
        return directory.resolve(commit.hex() + ".tags");
    }
}
