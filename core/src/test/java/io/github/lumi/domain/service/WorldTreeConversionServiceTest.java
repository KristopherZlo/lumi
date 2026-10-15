package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldTreeConversionServiceTest {
    @TempDir Path directory;

    @Test
    void malformedMappingFailsInsteadOfTreatingAnInterruptedRecordAsCompleted() throws Exception {
        ObjectId source = ObjectId.hash(new byte[] { 1 });
        var map = new HistoryConversionMap(directory);
        Path file = directory.resolve("conversion-map/section").resolve(source.hex().substring(0, 2))
                .resolve(source.hex().substring(2) + ".map");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.write(file, new byte[31]);
        assertThrows(IOException.class, () -> map.read(HistoryConversionMap.Kind.SECTION, source));
        java.nio.file.Files.write(file, new byte[33]);
        assertThrows(IOException.class, () -> map.read(HistoryConversionMap.Kind.SECTION, source));
    }

    @Test
    void convertsInheritedSharedLeavesAndResumesWithoutRewritingSourceObjects() throws Exception {
        var source = new WorldObjectRepository(directory.resolve("old"));
        var target = new WorldObjectRepository(directory.resolve("new"));
        var map = new HistoryConversionMap(directory.resolve("new"));
        ObjectId section = source.write(new SectionBlob(Collections.nCopies(4096, "minecraft:stone"), Map.of()));
        ObjectId entities = source.write(new EntityChunkBlob(List.of()));
        Map<HistoryKey, ObjectId> leaves = Map.of(new SectionKey(-33, 2, 64), section,
                new SectionKey(0, 2, 0), section, new EntityChunkKey(0, 0), entities);
        ObjectId tree = new MerkleTreeEditor(source).update(Optional.empty(), leaves);
        var calls = new AtomicInteger();
        var version = new WorldDataVersion(4786);
        var converter = new WorldPayloadConverter() {
            @Override public String recipeId() { return "test-converter-v1"; }
            public SectionBlob convert(SectionBlob value, WorldDataVersion dataVersion) {
                calls.incrementAndGet();
                return new SectionBlob(value.blockStates(), value.blockEntities(), dataVersion);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion dataVersion) {
                calls.incrementAndGet();
                return new EntityChunkBlob(value.entities(), dataVersion);
            }
        };
        var payloads = new WorldPayloadConversionService(version, converter);
        var interrupted = new WorldTreeConversionService(source, target, map, payloads,
                count -> { if (count == 1) throw new IllegalStateException("stop after a durable leaf"); });
        assertThrows(IllegalStateException.class, () -> interrupted.dimension(tree));
        var restarted = new WorldTreeConversionService(source, target,
                new HistoryConversionMap(directory.resolve("new")), payloads, ignored -> { });
        ObjectId converted = restarted.dimension(tree);
        assertEquals(2, calls.get());
        assertEquals(converted, restarted.dimension(tree));
        var result = new WorldObjectGraph(target).scan(converted).leaves();
        assertEquals(leaves.keySet(), result.keySet());
        assertEquals(version, target.readSection(result.get(new SectionKey(-33, 2, 64))).dataVersion());
        assertEquals(version, target.readEntities(result.get(new EntityChunkKey(0, 0))).dataVersion());
        assertEquals(WorldDataVersion.LEGACY_12111, source.readSection(section).dataVersion());
        assertEquals(leaves, new WorldObjectGraph(source).scan(tree).leaves());
        new HistoryConversionVerifier(directory.resolve("old"), directory.resolve("new"), map, payloads)
                .verify(ignored -> { });
        var changedAdapter = new WorldPayloadConverter() {
            @Override public String recipeId() { return "test-converter-v1"; }
            public SectionBlob convert(SectionBlob value, WorldDataVersion dataVersion) {
                return new SectionBlob(Collections.nCopies(4096, "minecraft:air"), value.blockEntities(), dataVersion);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion dataVersion) {
                return new EntityChunkBlob(value.entities(), dataVersion);
            }
        };
        assertThrows(IOException.class, () -> new HistoryConversionVerifier(
                directory.resolve("old"), directory.resolve("new"), map,
                new WorldPayloadConversionService(version, changedAdapter)).verify(ignored -> { }));
        assertThrows(IOException.class,
                () -> map.record(HistoryConversionMap.Kind.DIMENSION, tree, section));
    }
}
