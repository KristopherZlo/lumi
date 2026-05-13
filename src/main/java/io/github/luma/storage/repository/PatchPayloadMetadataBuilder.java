package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuilderChangeSurfacePolicy;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.minecraft.world.SectionChangeMask;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds lightweight metadata indexes for chunk-addressable patch payloads.
 */
final class PatchPayloadMetadataBuilder {

    private final BuilderChangeSurfacePolicy builderSurface = new BuilderChangeSurfacePolicy();

    int visibleChangeCount(List<StoredBlockChange> changes) {
        return this.builderSurface.visibleBlockChangeCount(changes);
    }

    List<SectionFingerprint> visibleSectionFingerprints(
            int chunkX,
            int chunkZ,
            List<StoredBlockChange> chunkChanges
    ) throws IOException {
        return this.sectionFingerprints(chunkX, chunkZ, this.builderSurface.visibleBlockChanges(chunkChanges));
    }

    List<SectionFingerprint> sectionFingerprints(
            int chunkX,
            int chunkZ,
            List<StoredBlockChange> chunkChanges
    ) throws IOException {
        if (chunkChanges == null || chunkChanges.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<StoredBlockChange>> bySection = new LinkedHashMap<>();
        for (StoredBlockChange change : chunkChanges) {
            bySection.computeIfAbsent(Math.floorDiv(change.pos().y(), 16), ignored -> new ArrayList<>()).add(change);
        }
        List<SectionFingerprint> fingerprints = new ArrayList<>(bySection.size());
        for (Map.Entry<Integer, List<StoredBlockChange>> entry : bySection.entrySet()) {
            List<StoredBlockChange> sorted = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(change -> sectionLocalIndex(change.pos())))
                    .toList();
            fingerprints.add(SectionFingerprint.fromBytes(
                    chunkX,
                    chunkZ,
                    entry.getKey(),
                    sorted.size(),
                    this.fingerprintBytes(sorted)
            ));
        }
        return List.copyOf(fingerprints);
    }

    private byte[] fingerprintBytes(List<StoredBlockChange> sectionChanges) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(sectionChanges.size());
            for (StoredBlockChange change : sectionChanges) {
                output.writeInt(sectionLocalIndex(change.pos()));
                StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().stateTag());
                StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().blockEntityTag());
                StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().stateTag());
                StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().blockEntityTag());
                output.writeBoolean(change.hidden());
            }
        }
        return bytes.toByteArray();
    }

    private static int sectionLocalIndex(BlockPoint pos) {
        return SectionChangeMask.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15);
    }
}
