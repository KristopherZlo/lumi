package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingIndexRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void laterGenerationSurvivesSaveCleanup() {
        WorkingIndex index = new WorkingIndex();
        SectionKey capturedKey = new SectionKey(1, 2, 3);
        EntityChunkKey unchangedKey = new EntityChunkKey(1, 3);
        index.markDirty(capturedKey);
        index.markDirty(unchangedKey);
        var captured = index.snapshot();

        index.markDirty(capturedKey);
        index.clearCaptured(captured);

        assertTrue(index.snapshot().generations().containsKey(capturedKey));
        assertFalse(index.snapshot().generations().containsKey(unchangedKey));
        assertEquals(2, index.snapshot().generations().get(capturedKey));
    }

    @Test
    void persistsExactGenerationsAcrossRestart() throws IOException {
        WorkingIndex index = new WorkingIndex();
        index.markDirty(new SectionKey(-4, 7, 12));
        index.markDirty(new EntityChunkKey(9, -2));
        index.markDirty(new EntityChunkKey(9, -2));
        WorkingIndexRepository repository = new WorkingIndexRepository(repositoryRoot);

        repository.write(index.snapshot());

        assertEquals(index.snapshot(), new WorkingIndexRepository(repositoryRoot).read());
    }
}
