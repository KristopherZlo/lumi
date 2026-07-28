package io.github.lumi.domain.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record ObjectId(String hex) {
    public static final int HEX_LENGTH = 64;

    public ObjectId {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != HEX_LENGTH || !isLowerHex(hex)) {
            throw new IllegalArgumentException("Object ID must be 64 lowercase hexadecimal characters");
        }
    }

    private static boolean isLowerHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || (character > '9'
                    && (character < 'a' || character > 'f'))) {
                return false;
            }
        }
        return true;
    }

    public static ObjectId hash(byte[] canonicalPayload) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload);
            return new ObjectId(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}
