package io.github.lumi.storage.object;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
}
