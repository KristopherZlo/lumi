package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChunkSkylightRefreshQueueTest {

    @Test
    void refreshesSectionStatusesBeforeChunkSkySources() {
        ChunkSkylightRefreshQueue queue = new ChunkSkylightRefreshQueue(
                List.of(new ChunkPoint(0, 0), new ChunkPoint(1, 0)),
                List.of(SectionPos.of(0, 4, 0), SectionPos.of(1, 4, 0))
        );
        RecordingRefreshAccess access = new RecordingRefreshAccess();

        ChunkSkylightRefreshQueue.RefreshTickResult first = queue.drain(access, 1, 10, Long.MAX_VALUE);
        ChunkSkylightRefreshQueue.RefreshTickResult second = queue.drain(access, 10, 10, Long.MAX_VALUE);

        Assertions.assertEquals(1, first.sectionUpdates());
        Assertions.assertEquals(0, first.refreshedChunks());
        Assertions.assertFalse(first.complete());
        Assertions.assertEquals(1, second.sectionUpdates());
        Assertions.assertEquals(2, second.refreshedChunks());
        Assertions.assertTrue(second.complete());
        Assertions.assertEquals(
                List.of("section:0:4:0", "section:1:4:0", "chunk:0:0", "chunk:1:0"),
                access.events
        );
    }

    @Test
    void reportsMissingSectionsAndChunks() {
        ChunkSkylightRefreshQueue queue = new ChunkSkylightRefreshQueue(
                List.of(new ChunkPoint(0, 0)),
                List.of(SectionPos.of(0, 4, 0))
        );
        RecordingRefreshAccess access = new RecordingRefreshAccess(false, false);

        ChunkSkylightRefreshQueue.RefreshTickResult result = queue.drain(access, 10, 10, Long.MAX_VALUE);

        Assertions.assertEquals(1, result.missingSections());
        Assertions.assertEquals(1, result.missingChunks());
        Assertions.assertTrue(result.complete());
    }

    private static final class RecordingRefreshAccess implements ChunkSkylightRefreshQueue.RefreshAccess {

        private final boolean sectionsAvailable;
        private final boolean chunksAvailable;
        private final List<String> events = new ArrayList<>();

        private RecordingRefreshAccess() {
            this(true, true);
        }

        private RecordingRefreshAccess(boolean sectionsAvailable, boolean chunksAvailable) {
            this.sectionsAvailable = sectionsAvailable;
            this.chunksAvailable = chunksAvailable;
        }

        @Override
        public boolean refreshSectionStatus(SectionPos section) {
            this.events.add("section:" + section.x() + ":" + section.y() + ":" + section.z());
            return this.sectionsAvailable;
        }

        @Override
        public boolean refreshChunkSkySources(ChunkPoint chunk) {
            this.events.add("chunk:" + chunk.x() + ":" + chunk.z());
            return this.chunksAvailable;
        }
    }
}
