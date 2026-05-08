package io.github.luma.domain.service;

import io.github.luma.domain.model.SectionFingerprint;

public final class HistoryFingerprintService {

    public SectionFingerprint fingerprintSection(
            int chunkX,
            int chunkZ,
            int sectionY,
            int changedCount,
            byte[] bytes
    ) {
        return SectionFingerprint.fromBytes(chunkX, chunkZ, sectionY, changedCount, bytes);
    }

    public boolean matchingSection(SectionFingerprint left, SectionFingerprint right) {
        return left != null
                && right != null
                && left.sameSection(right)
                && left.changedCount() == right.changedCount()
                && left.xxHash64() == right.xxHash64()
                && left.sha256().equals(right.sha256());
    }
}
