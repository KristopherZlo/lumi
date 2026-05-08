package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import net.minecraft.core.SectionPos;

final class ChunkSkylightRefreshQueue {

    private final List<ChunkPoint> chunks;
    private final List<SectionPos> sections;
    private int nextChunkIndex = 0;
    private int nextSectionIndex = 0;

    ChunkSkylightRefreshQueue(List<ChunkPoint> chunks, List<SectionPos> sections) {
        this.chunks = chunks == null ? List.of() : List.copyOf(chunks);
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    boolean required() {
        return !this.chunks.isEmpty() || !this.sections.isEmpty();
    }

    boolean complete() {
        return this.remainingSections() <= 0 && this.remainingChunks() <= 0;
    }

    int sectionCount() {
        return this.sections.size();
    }

    int chunkCount() {
        return this.chunks.size();
    }

    RefreshTickResult drain(RefreshAccess access, int maxSections, int maxChunks, long deadlineNanos) {
        if (access == null || this.complete()) {
            return RefreshTickResult.empty(this.remainingSections(), this.remainingChunks(), this.complete());
        }
        int sectionUpdates = 0;
        int missingSections = 0;
        int attemptedSections = 0;
        while (this.nextSectionIndex < this.sections.size()
                && attemptedSections < maxSections
                && System.nanoTime() < deadlineNanos) {
            SectionPos section = this.sections.get(this.nextSectionIndex);
            if (access.refreshSectionStatus(section)) {
                sectionUpdates += 1;
            } else {
                missingSections += 1;
            }
            this.nextSectionIndex += 1;
            attemptedSections += 1;
        }

        int refreshedChunks = 0;
        int missingChunks = 0;
        int attemptedChunks = 0;
        if (this.remainingSections() <= 0) {
            while (this.nextChunkIndex < this.chunks.size()
                    && attemptedChunks < maxChunks
                    && System.nanoTime() < deadlineNanos) {
                ChunkPoint chunk = this.chunks.get(this.nextChunkIndex);
                if (access.refreshChunkSkySources(chunk)) {
                    refreshedChunks += 1;
                } else {
                    missingChunks += 1;
                }
                this.nextChunkIndex += 1;
                attemptedChunks += 1;
            }
        }

        return new RefreshTickResult(
                sectionUpdates,
                missingSections,
                attemptedSections,
                refreshedChunks,
                missingChunks,
                attemptedChunks,
                this.remainingSections(),
                this.remainingChunks(),
                this.complete()
        );
    }

    private int remainingSections() {
        return Math.max(0, this.sections.size() - this.nextSectionIndex);
    }

    private int remainingChunks() {
        return Math.max(0, this.chunks.size() - this.nextChunkIndex);
    }

    interface RefreshAccess {

        boolean refreshSectionStatus(SectionPos section);

        boolean refreshChunkSkySources(ChunkPoint chunk);
    }

    record RefreshTickResult(
            int sectionUpdates,
            int missingSections,
            int attemptedSections,
            int refreshedChunks,
            int missingChunks,
            int attemptedChunks,
            int remainingSections,
            int remainingChunks,
            boolean complete
    ) {

        private static RefreshTickResult empty(int remainingSections, int remainingChunks, boolean complete) {
            return new RefreshTickResult(0, 0, 0, 0, 0, 0, remainingSections, remainingChunks, complete);
        }
    }

}
