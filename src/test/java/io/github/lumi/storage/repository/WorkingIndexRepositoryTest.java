package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

    @Test
    void persistsBuilderGenerationSubsetInTheSameAtomicIndex() throws IOException {
        SectionKey builder = new SectionKey(-4, 7, 12);
        EntityChunkKey ambient = new EntityChunkKey(9, -2);
        WorkingIndexRepository.State state = new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(builder, 3L, ambient, 2L)),
                new WorkingIndexSnapshot(Map.of(builder, 2L)));

        WorkingIndexRepository repository = new WorkingIndexRepository(repositoryRoot);
        repository.write(state);

        assertEquals(state, new WorkingIndexRepository(repositoryRoot).readState());
    }

    @Test
    void recoveryCleanupPreservesAmbientAndNewerSameKeyGenerations() throws IOException {
        SectionKey newer = new SectionKey(1, 2, 3);
        EntityChunkKey captured = new EntityChunkKey(4, 5);
        EntityChunkKey ambient = new EntityChunkKey(6, 7);
        WorkingIndexRepository repository = new WorkingIndexRepository(repositoryRoot);
        repository.write(new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(newer, 4L, captured, 2L, ambient, 3L)),
                new WorkingIndexSnapshot(Map.of(newer, 4L, captured, 2L))));

        repository.clearCaptured(new WorkingIndexSnapshot(Map.of(newer, 3L, captured, 2L)));

        assertEquals(new WorkingIndexRepository.State(
                        new WorkingIndexSnapshot(Map.of(newer, 4L, ambient, 3L)),
                        new WorkingIndexSnapshot(Map.of(newer, 4L))),
                repository.readState());
    }

    @Test
    void readsLegacyLwi2DirtyKeysConservativelyAsBuilderWork() throws IOException {
        SectionKey key = new SectionKey(-4, 7, 12);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4C574932);
            output.writeInt(1);
            output.writeByte(1);
            output.writeInt(key.chunkX());
            output.writeInt(key.chunkZ());
            output.writeInt(key.sectionY());
            output.writeLong(3L);
        }
        Path file = repositoryRoot.resolve("working").resolve("index.bin");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());

        WorkingIndexRepository.State state =
                new WorkingIndexRepository(repositoryRoot).readState();

        WorkingIndexSnapshot expected = new WorkingIndexSnapshot(Map.of(key, 3L));
        assertEquals(expected, state.working());
        assertEquals(expected, state.builder());
    }
}
