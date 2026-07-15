package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.SectionFingerprint;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Resolves and filters the section subset requested from a patch payload. */
final class PatchSectionSelector {

    Selection selection(Collection<SectionFingerprint> sections) {
        Set<String> sectionKeys = new HashSet<>();
        Set<ChunkPoint> chunks = new HashSet<>();
        for (SectionFingerprint section : sections) {
            if (section == null) {
                continue;
            }
            sectionKeys.add(key(section.chunkX(), section.chunkZ(), section.sectionY()));
            chunks.add(section.chunk());
        }
        return new Selection(Set.copyOf(sectionKeys), Set.copyOf(chunks));
    }

    record Selection(Set<String> sectionKeys, Set<ChunkPoint> chunks) {

        boolean isEmpty() {
            return this.sectionKeys.isEmpty();
        }

        PatchSectionWorldChanges filter(PatchSectionWorldChanges changes) {
            return new PatchSectionWorldChanges(
                    changes.sectionFrames().stream()
                            .filter(frame -> this.sectionKeys.contains(
                                    key(frame.chunkX(), frame.chunkZ(), frame.sectionY())))
                            .toList(),
                    changes.entityChanges()
            );
        }
    }

    private static String key(int chunkX, int chunkZ, int sectionY) {
        return chunkX + ":" + chunkZ + ":" + sectionY;
    }
}
