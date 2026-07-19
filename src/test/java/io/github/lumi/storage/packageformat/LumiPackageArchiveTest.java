package io.github.lumi.storage.packageformat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LumiPackageArchiveTest {
    @TempDir Path directory;

    @Test
    void writesAndRevalidatesEveryCanonicalPayload() throws Exception {
        byte[] commit = "commit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CommitId commitId = new CommitId(ObjectId.hash(commit));
        Map<ObjectId, byte[]> payloads = new LinkedHashMap<>();
        payloads.put(ObjectId.hash(second), second);
        payloads.put(ObjectId.hash(first), first);
        var manifest = new LumiPackageManifest(
                "minecraft:overworld", commitId, commit.length,
                payloads.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().length)));
        Path target = directory.resolve("clock.lumi");
        LumiPackageArchive archive = new LumiPackageArchive();

        archive.write(target, manifest, commit, payloads::get);

        Map<ObjectId, byte[]> restored = new LinkedHashMap<>();
        LumiPackageManifest read = archive.read(target, new LumiPackageArchive.PayloadConsumer() {
            @Override public void commit(CommitId id, byte[] payload) {
                assertEquals(commitId, id);
                assertArrayEquals(commit, payload);
            }
            @Override public void object(ObjectId id, byte[] payload) {
                restored.put(id, payload);
            }
        });
        assertEquals(manifest, read);
        payloads.forEach((id, payload) -> assertArrayEquals(payload, restored.get(id)));
    }

    @Test
    void rejectsUnexpectedPathsAndPayloadHashMismatch() throws Exception {
        Path traversal = directory.resolve("traversal.lumi");
        writeZip(traversal, Map.of("../manifest.bin", new byte[] {1}));
        assertThrows(IOException.class,
                () -> new LumiPackageArchive().read(traversal, discard()));

        byte[] commit = "commit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectId expected = ObjectId.hash("expected".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        var manifest = new LumiPackageManifest(
                "minecraft:overworld", new CommitId(ObjectId.hash(commit)),
                commit.length, Map.of(expected, 6));
        Path corrupt = directory.resolve("corrupt.lumi");
        Map<String, byte[]> corruptEntries = new LinkedHashMap<>();
        corruptEntries.put(
                "manifest.bin", new LumiPackageManifestCodec().encode(manifest));
        corruptEntries.put("commits/" + manifest.commit().hex() + ".bin", commit);
        corruptEntries.put("objects/" + expected.hex() + ".bin", "broken".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        writeZip(corrupt, corruptEntries);
        assertThrows(IOException.class,
                () -> new LumiPackageArchive().read(corrupt, discard()));
    }

    @Test
    void writesAndValidatesAnOptionalPreview() throws Exception {
        byte[] commit = "commit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] preview = "png-preview".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        CommitId commitId = new CommitId(ObjectId.hash(commit));
        var manifest = new LumiPackageManifest(
                "minecraft:overworld", commitId, commit.length, Map.of(),
                Optional.of(new LumiPackageManifest.Preview(
                        ObjectId.hash(preview), preview.length)));
        Path target = directory.resolve("preview.lumi");
        LumiPackageArchive archive = new LumiPackageArchive();

        archive.write(
                target, manifest, commit, ignored -> null, Optional.of(preview));

        byte[][] restored = new byte[1][];
        assertEquals(manifest, archive.read(
                target, new LumiPackageArchive.PayloadConsumer() {
                    @Override public void commit(CommitId id, byte[] payload) { }
                    @Override public void object(ObjectId id, byte[] payload) { }
                    @Override public void preview(CommitId id, byte[] png) {
                        restored[0] = png;
                    }
                }));
        assertArrayEquals(preview, restored[0]);
    }

    private static LumiPackageArchive.PayloadConsumer discard() {
        return new LumiPackageArchive.PayloadConsumer() {
            @Override public void commit(CommitId id, byte[] payload) { }
            @Override public void object(ObjectId id, byte[] payload) { }
        };
    }

    private static void writeZip(Path path, Map<String, byte[]> entries)
            throws IOException {
        try (var output = new java.util.zip.ZipOutputStream(
                java.nio.file.Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
