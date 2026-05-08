package io.github.luma.domain.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.jpountz.xxhash.XXHashFactory;

public record SectionFingerprint(
        int chunkX,
        int chunkZ,
        int sectionY,
        int changedCount,
        long xxHash64,
        String sha256
) {

    private static final long XXHASH_SEED = 0x4c554d4953454354L;

    public SectionFingerprint {
        sha256 = sha256 == null ? "" : sha256;
    }

    public static SectionFingerprint fromBytes(
            int chunkX,
            int chunkZ,
            int sectionY,
            int changedCount,
            byte[] bytes
    ) {
        byte[] payload = bytes == null ? new byte[0] : bytes;
        long xxHash64 = XXHashFactory.fastestInstance().hash64().hash(
                payload,
                0,
                payload.length,
                XXHASH_SEED
        );
        return new SectionFingerprint(chunkX, chunkZ, sectionY, changedCount, xxHash64, sha256(payload));
    }

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }

    public boolean sameSection(SectionFingerprint other) {
        return other != null
                && this.chunkX == other.chunkX
                && this.chunkZ == other.chunkZ
                && this.sectionY == other.sectionY;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >>> 4) & 15, 16));
                builder.append(Character.forDigit(value & 15, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
