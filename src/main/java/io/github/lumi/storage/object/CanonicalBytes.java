package io.github.lumi.storage.object;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import io.github.lumi.domain.model.ObjectId;

final class CanonicalBytes {
    private CanonicalBytes() {
    }

    static void write(DataOutputStream output, byte[] value, int maximum, String label) throws IOException {
        if (value.length > maximum) {
            throw new IOException(label + " exceeds " + maximum + " bytes");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] read(DataInputStream input, int maximum, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Invalid " + label + " length");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new IOException("Truncated " + label);
        }
        return value;
    }

    static void writeString(DataOutputStream output, String value, int maximum, String label) throws IOException {
        write(output, value.getBytes(StandardCharsets.UTF_8), maximum, label);
    }

    static String readString(DataInputStream input, int maximum, String label) throws IOException {
        return new String(read(input, maximum, label), StandardCharsets.UTF_8);
    }

    static void writeId(DataOutputStream output, ObjectId id) throws IOException {
        output.write(HexFormat.of().parseHex(id.hex()));
    }

    static ObjectId readId(DataInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(32);
        if (bytes.length != 32) {
            throw new IOException("Truncated object ID");
        }
        return new ObjectId(HexFormat.of().formatHex(bytes));
    }

    static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
