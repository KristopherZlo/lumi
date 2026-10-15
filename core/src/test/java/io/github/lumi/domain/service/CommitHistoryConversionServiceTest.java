package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.*;
import io.github.lumi.storage.repository.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitHistoryConversionServiceTest {
    @TempDir Path directory;

    @Test
    void coalescingWorldStatesPreservesDistinctVersionsAndBothMergeParents() throws Exception {
        Path old = directory.resolve("old"), converted = directory.resolve("new");
        var objects = new WorldObjectRepository(old);
        var editor = new MerkleTreeEditor(objects);
        var key = new SectionKey(0, 0, 0);
        var leftTree = editor.update(Optional.empty(), Map.of(key, objects.write(
                SectionBlob.fromPalette(List.of("minecraft:stone"), new short[4096], Map.of()))));
        var rightTree = editor.update(Optional.empty(), Map.of(key, objects.write(
                SectionBlob.fromPalette(List.of("minecraft:dirt"), new short[4096], Map.of()))));
        var source = new CommitRepository(old);
        var left = source.write(commit(leftTree, List.of(), "Same metadata"));
        var right = source.write(commit(rightTree, List.of(), "Same metadata"));
        var merge = source.write(commit(rightTree, List.of(left, right), "Merge"));
        var adapter = new WorldPayloadConverter() {
            @Override public String recipeId() { return "test-converter-v1"; }
            public SectionBlob convert(SectionBlob value, WorldDataVersion version) {
                return SectionBlob.fromPalette(List.of("minecraft:air"), new short[4096], Map.of(), version);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion version) {
                return new EntityChunkBlob(value.entities(), version);
            }
        };
        var map = new HistoryConversionMap(converted);
        var payloads = new WorldPayloadConversionService(new WorldDataVersion(4786), adapter);
        var trees = new WorldTreeConversionService(objects, new WorldObjectRepository(converted), map, payloads, ignored -> { });
        var service = new CommitHistoryConversionService(old, converted, map, trees, ignored -> { });
        var migrated = new CommitRepository(converted).read(service.convert(merge));
        var target = new CommitRepository(converted);
        var leftResult = target.read(migrated.parents().get(0));
        var rightResult = target.read(migrated.parents().get(1));
        assertEquals(leftResult.tree(), rightResult.tree());
        org.junit.jupiter.api.Assertions.assertNotEquals(migrated.parents().get(0), migrated.parents().get(1));
        assertEquals(Optional.of(left), leftResult.conversionOrigin());
        assertEquals(Optional.of(right), rightResult.conversionOrigin());
        new HistoryConversionVerifier(old, converted, map, payloads).verify(ignored -> { });
        Path next = directory.resolve("next");
        var nextMap = new HistoryConversionMap(next);
        var nextPayloads = new WorldPayloadConversionService(new WorldDataVersion(4903), adapter);
        var nextTrees = new WorldTreeConversionService(new WorldObjectRepository(converted), new WorldObjectRepository(next),
                nextMap, nextPayloads, ignored -> { });
        var nextService = new CommitHistoryConversionService(converted, next, nextMap, nextTrees, ignored -> { });
        var nextCommit = new CommitRepository(next).read(nextService.convert(migrated.parents().get(0)));
        assertEquals(Optional.of(left), nextCommit.conversionOrigin());
    }

    @Test
    void interruptedMergeConversionResumesWithSharedParentsAndAllSidecars() throws Exception {
        Path old = directory.resolve("old"), converted = directory.resolve("new");
        var objects = new WorldObjectRepository(old);
        var section = objects.write(new SectionBlob(Collections.nCopies(4096, "minecraft:stone"), Map.of()));
        var tree = new MerkleTreeEditor(objects).update(Optional.empty(), Map.of(new SectionKey(0, 0, 0), section));
        var source = new CommitRepository(old);
        CommitId initial = source.write(commit(tree, List.of(), "Initial"));
        CommitId left = source.write(commit(tree, List.of(initial), "Left"));
        CommitId right = source.write(commit(tree, List.of(initial), "Right"));
        CommitId merge = source.write(commit(tree, List.of(left, right), "Merge"));
        var name = new VersionDisplayName("Working clock");
        var tags = new VersionTags(List.of("working", "before-upgrade"));
        new VersionDisplayNameRepository(old).replace(merge, name);
        new VersionTagRepository(old).replace(merge, tags);
        new VersionPreviewRepository(old).save(merge, new byte[] { 1, 2, 3 });
        var map = new HistoryConversionMap(converted);
        var trees = trees(old, converted, map);
        var interrupted = new CommitHistoryConversionService(old, converted, map, trees,
                count -> { if (count == 1) throw new IllegalStateException("restart after ancestor publication"); });
        assertThrows(IllegalStateException.class, () -> interrupted.convert(merge));
        var restarted = new CommitHistoryConversionService(old, converted,
                new HistoryConversionMap(converted), trees(old, converted, map), ignored -> { });
        CommitId result = restarted.convert(merge);
        var target = new CommitRepository(converted);
        Commit migrated = target.read(result);
        var expected = commit(migrated.tree(), List.of(restarted.convert(left), restarted.convert(right)), "Merge");
        assertEquals(new Commit(expected.tree(), expected.parents(), expected.author(), expected.message(), expected.timestamp(),
                expected.workspaceId(), expected.zoneId(), expected.kind(), expected.statistics(), expected.playerSpawns(),
                Optional.of(merge)), migrated);
        assertEquals(List.of(restarted.convert(initial)), target.read(migrated.parents().getFirst()).parents());
        assertEquals(name, new VersionDisplayNameRepository(converted).read(result).orElseThrow());
        assertEquals(tags, new VersionTagRepository(converted).read(result));
        assertEquals(List.of(initial), source.read(left).parents());
        assertEquals(3, new VersionPreviewRepository(converted).load(result).orElseThrow().length);
        assertEquals(result, restarted.convert(merge));
        var adapter = new WorldPayloadConverter() {
            @Override public String recipeId() { return "test-converter-v1"; }
            public SectionBlob convert(SectionBlob value, WorldDataVersion version) {
                return new SectionBlob(value.blockStates(), value.blockEntities(), version);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion version) {
                return new EntityChunkBlob(value.entities(), version);
            }
        };
        var verifier = new HistoryConversionVerifier(old, converted, map,
                new WorldPayloadConversionService(new WorldDataVersion(4786), adapter));
        verifier.verify(ignored -> { });
        new VersionTagRepository(converted).replace(result, VersionTags.empty());
        assertThrows(IOException.class, () -> verifier.verify(ignored -> { }));
        try (var files = java.nio.file.Files.list(converted.resolve("conversion-work"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void deepHistoryUsesTheDiskStackAndMissingAncestorsRefuseCompletion() throws Exception {
        Path old = directory.resolve("old"), converted = directory.resolve("new");
        var source = new CommitRepository(old);
        var tree = new WorldObjectRepository(old).write(new DimensionTree(Map.of()));
        CommitId head = source.write(commit(tree, List.of(), "0"));
        for (int index = 1; index <= 128; index++) head = source.write(commit(tree, List.of(head), Integer.toString(index)));
        var map = new HistoryConversionMap(converted);
        var service = new CommitHistoryConversionService(old, converted, map, trees(old, converted, map), ignored -> { });
        assertEquals("128", new CommitRepository(converted).read(service.convert(head)).message());
        var missing = new CommitId(ObjectId.hash(new byte[] { 7 }));
        var broken = source.write(commit(tree, List.of(missing), "Broken parent"));
        assertThrows(IOException.class, () -> service.convert(broken));
        assertTrue(map.read(HistoryConversionMap.Kind.COMMIT, broken.value()).isEmpty());
    }

    private static Commit commit(ObjectId tree, List<CommitId> parents, String message) {
        return new Commit(tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"), message,
                Instant.EPOCH, new UUID(0, 2), Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(1, 0, 4096, 0));
    }

    private static WorldTreeConversionService trees(Path source, Path target, HistoryConversionMap map) {
        var adapter = new WorldPayloadConverter() {
            @Override public String recipeId() { return "test-converter-v1"; }
            public SectionBlob convert(SectionBlob value, WorldDataVersion version) {
                return new SectionBlob(value.blockStates(), value.blockEntities(), version);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion version) {
                return new EntityChunkBlob(value.entities(), version);
            }
        };
        return new WorldTreeConversionService(new WorldObjectRepository(source), new WorldObjectRepository(target),
                map, new WorldPayloadConversionService(new WorldDataVersion(4786), adapter), ignored -> { });
    }
}
