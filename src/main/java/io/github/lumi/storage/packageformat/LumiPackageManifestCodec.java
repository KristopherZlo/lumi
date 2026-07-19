package io.github.lumi.storage.packageformat;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Canonical binary codec for backward-compatible .lumi package manifests. */
public final class LumiPackageManifestCodec {
    private static final int MAGIC = 0x4C504B32;
    private static final int SCHEMA = 2;
    private static final int MAX_DIMENSION_BYTES = 256;

    public byte[] encode(LumiPackageManifest manifest) throws IOException {
        byte[] dimension = manifest.dimensionId().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            output.writeInt(dimension.length);
            output.write(dimension);
            writeId(output, manifest.commit().value());
            output.writeInt(manifest.commitBytes());
            output.writeInt(manifest.objects().size());
            for (var entry : manifest.objects().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(
                            java.util.Comparator.comparing(ObjectId::hex))).toList()) {
                writeId(output, entry.getKey());
                output.writeInt(entry.getValue());
            }
            output.writeBoolean(manifest.preview().isPresent());
            if (manifest.preview().isPresent()) {
                var preview = manifest.preview().orElseThrow();
                writeId(output, preview.hash());
                output.writeInt(preview.bytes());
            }
        }
        return bytes.toByteArray();
    }

    public LumiPackageManifest decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Unsupported Lumi package manifest");
            }
            int schema = input.readInt();
            if (schema < 1 || schema > SCHEMA) {
                throw new IOException("Unsupported Lumi package manifest");
            }
            int dimensionLength = input.readInt();
            if (dimensionLength < 1 || dimensionLength > MAX_DIMENSION_BYTES) {
                throw new IOException("Invalid package dimension length");
            }
            byte[] dimension = input.readNBytes(dimensionLength);
            if (dimension.length != dimensionLength) {
                throw new IOException("Truncated package dimension");
            }
            CommitId commit = new CommitId(readId(input));
            int commitBytes = input.readInt();
            int count = input.readInt();
            if (count < 0 || count > LumiPackageManifest.MAX_OBJECTS) {
                throw new IOException("Invalid package object count");
            }
            var objects = new LinkedHashMap<ObjectId, Integer>();
            ObjectId previous = null;
            for (int index = 0; index < count; index++) {
                ObjectId id = readId(input);
                int size = input.readInt();
                if (previous != null && previous.hex().compareTo(id.hex()) >= 0) {
                    throw new IOException("Package objects are not canonical");
                }
                previous = id;
                objects.put(id, size);
            }
            java.util.Optional<LumiPackageManifest.Preview> preview =
                    java.util.Optional.empty();
            if (schema >= 2 && input.readBoolean()) {
                preview = java.util.Optional.of(
                        new LumiPackageManifest.Preview(
                                readId(input), input.readInt()));
            }
            if (input.available() != 0) {
                throw new IOException("Trailing package manifest bytes");
            }
            return new LumiPackageManifest(
                    new String(dimension, StandardCharsets.UTF_8),
                    commit, commitBytes, objects, preview);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Lumi package manifest", invalid);
        }
    }

    private static void writeId(DataOutputStream output, ObjectId id)
            throws IOException {
        output.write(HexFormat.of().parseHex(id.hex()));
    }

    private static ObjectId readId(DataInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(32);
        if (bytes.length != 32) {
            throw new IOException("Truncated package object ID");
        }
        return new ObjectId(HexFormat.of().formatHex(bytes));
    }
}
