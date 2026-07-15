package io.github.lumi.domain.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record ObjectId(String hex) {
    public static final int HEX_LENGTH = 64;

    public ObjectId {
        Objects.requireNonNull(hex, "hex");
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Object ID must be 64 lowercase hexadecimal characters");
        }
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
