package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionDisplayName;
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
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Atomic mutable display names keyed by immutable commit identity. */
public final class VersionDisplayNameRepository {
    private static final int MAGIC = 0x4C564E32;
    private static final int MAX_NAME_BYTES = VersionDisplayName.MAX_LENGTH * 4;
    private static final int HEADER_BYTES = Integer.BYTES + 32 + Short.BYTES;
    private final Path directory;

    public VersionDisplayNameRepository(Path dimensionRepository) {
        directory = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("names");
    }

    public synchronized Optional<VersionDisplayName> read(CommitId commit)
            throws IOException {
        CommitId expected = Objects.requireNonNull(commit, "commit");
        Path file = path(expected);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        long size = Files.size(file);
        if (size <= HEADER_BYTES || size > HEADER_BYTES + MAX_NAME_BYTES) {
            throw new IOException("Invalid Lumi version name file size: " + size);
        }
        return Optional.of(decode(expected, RepositoryFileReader.read(
                file, HEADER_BYTES + MAX_NAME_BYTES)));
    }

    public synchronized void replace(CommitId commit, VersionDisplayName name)
            throws IOException {
        AtomicFileWriter.replace(
                path(Objects.requireNonNull(commit, "commit")),
                encode(commit, Objects.requireNonNull(name, "name")));
    }

    private static byte[] encode(CommitId commit, VersionDisplayName name)
            throws IOException {
        byte[] encoded = name.value().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_NAME_BYTES) {
            throw new IOException("Version name UTF-8 payload is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.write(HexFormat.of().parseHex(commit.hex()));
            output.writeShort(encoded.length);
            output.write(encoded);
        }
        return bytes.toByteArray();
    }

    private static VersionDisplayName decode(CommitId expected, byte[] payload)
            throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 version name file");
            }
            byte[] commitBytes = input.readNBytes(32);
            if (commitBytes.length != 32) {
                throw new IOException("Truncated version name commit ID");
            }
            CommitId stored = new CommitId(
                    new ObjectId(HexFormat.of().formatHex(commitBytes)));
            if (!stored.equals(expected)) {
                throw new IOException("Version name filename and payload disagree");
            }
            int length = input.readUnsignedShort();
            if (length < 1 || length > MAX_NAME_BYTES) {
                throw new IOException("Invalid version name length");
            }
            byte[] encoded = input.readNBytes(length);
            if (encoded.length != length || input.available() != 0) {
                throw new IOException("Invalid version name payload");
            }
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded)).toString();
            return new VersionDisplayName(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid version name file", invalid);
        }
    }

    private Path path(CommitId commit) {
        return directory.resolve(commit.hex() + ".name");
    }
}
