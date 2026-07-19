package io.github.lumi.storage.packageformat;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Atomic ZIP transport with exact path, size and content validation. */
public final class LumiPackageArchive {
    private static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES =
            LumiPackageManifest.MAX_TOTAL_BYTES + 1024L * 1024 * 1024;
    private final LumiPackageManifestCodec manifests = new LumiPackageManifestCodec();

    public void write(
            Path target,
            LumiPackageManifest manifest,
            byte[] commit,
            PayloadReader objects) throws IOException {
        write(target, manifest, commit, objects, Optional.empty());
    }

    public void write(
            Path target,
            LumiPackageManifest manifest,
            byte[] commit,
            PayloadReader objects,
            Optional<byte[]> preview) throws IOException {
        Path output = packagePath(target);
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(objects, "objects");
        preview = Objects.requireNonNull(preview, "preview");
        validate(manifest.commit().value(), manifest.commitBytes(), commit, "commit");
        if (manifest.preview().isPresent() != preview.isPresent()) {
            throw new IOException("Package preview and manifest disagree");
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".lumi-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                    ZipOutputStream zip = new ZipOutputStream(
                            Channels.newOutputStream(channel))) {
                writeEntry(zip, "manifest.bin", manifests.encode(manifest));
                writeEntry(zip, commitPath(manifest.commit()), commit);
                for (var entry : manifest.objects().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey(
                                Comparator.comparing(ObjectId::hex))).toList()) {
                    byte[] payload = Objects.requireNonNull(
                            objects.read(entry.getKey()), "object payload");
                    validate(entry.getKey(), entry.getValue(), payload, "object");
                    writeEntry(zip, objectPath(entry.getKey()), payload);
                }
                if (preview.isPresent()) {
                    byte[] payload = preview.orElseThrow();
                    var expected = manifest.preview().orElseThrow();
                    validate(expected.hash(), expected.bytes(), payload, "preview");
                    writeEntry(zip, previewPath(manifest.commit()), payload);
                }
                zip.finish();
                zip.flush();
                channel.force(true);
            }
            try {
                Files.move(temporary, output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "Lumi package export requires atomic moves", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public LumiPackageManifest read(Path source, PayloadConsumer consumer)
            throws IOException {
        return read(source, null, consumer);
    }

    public LumiPackageManifest read(
            Path source,
            LumiPackageManifest expectedManifest,
            PayloadConsumer consumer) throws IOException {
        Path input = packagePath(source);
        Objects.requireNonNull(consumer, "consumer");
        long archiveBytes = Files.size(input);
        if (archiveBytes < 1 || archiveBytes > MAX_ARCHIVE_BYTES) {
            throw new IOException("Invalid Lumi package file size");
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(input))) {
            requireEntry(zip.getNextEntry(), "manifest.bin");
            LumiPackageManifest manifest =
                    manifests.decode(readBounded(zip, MAX_MANIFEST_BYTES));
            zip.closeEntry();
            if (expectedManifest != null && !manifest.equals(expectedManifest)) {
                throw new IOException("Lumi package changed after confirmation");
            }

            requireEntry(zip.getNextEntry(), commitPath(manifest.commit()));
            byte[] commit = readExact(zip, manifest.commitBytes());
            validate(manifest.commit().value(), manifest.commitBytes(), commit, "commit");
            consumer.commit(manifest.commit(), commit);
            zip.closeEntry();

            for (var expected : manifest.objects().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(
                            Comparator.comparing(ObjectId::hex))).toList()) {
                requireEntry(zip.getNextEntry(), objectPath(expected.getKey()));
                byte[] payload = readExact(zip, expected.getValue());
                validate(expected.getKey(), expected.getValue(), payload, "object");
                consumer.object(expected.getKey(), payload);
                zip.closeEntry();
            }
            if (manifest.preview().isPresent()) {
                var expected = manifest.preview().orElseThrow();
                requireEntry(zip.getNextEntry(), previewPath(manifest.commit()));
                byte[] payload = readExact(zip, expected.bytes());
                validate(expected.hash(), expected.bytes(), payload, "preview");
                consumer.preview(manifest.commit(), payload);
                zip.closeEntry();
            }
            if (zip.getNextEntry() != null) {
                throw new IOException("Unexpected entry in Lumi package");
            }
            return manifest;
        }
    }

    private static Path packagePath(Path value) {
        Path path = Objects.requireNonNull(value, "path").toAbsolutePath().normalize();
        if (!path.getFileName().toString().endsWith(".lumi")) {
            throw new IllegalArgumentException("Lumi package must use the .lumi extension");
        }
        return path;
    }

    private static void requireEntry(ZipEntry entry, String expected)
            throws IOException {
        if (entry == null || entry.isDirectory() || !entry.getName().equals(expected)) {
            throw new IOException("Expected Lumi package entry: " + expected);
        }
    }

    private static byte[] readExact(ZipInputStream input, int expected)
            throws IOException {
        byte[] payload = input.readNBytes(Math.addExact(expected, 1));
        if (payload.length != expected) {
            throw new IOException("Lumi package entry size does not match manifest");
        }
        return payload;
    }

    private static byte[] readBounded(ZipInputStream input, int maximum)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() > maximum - read) {
                throw new IOException("Lumi package manifest is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validate(
            ObjectId expected, int expectedBytes, byte[] payload, String kind)
            throws IOException {
        if (payload.length != expectedBytes) {
            throw new IOException("Lumi package " + kind + " size mismatch");
        }
        if (!ObjectId.hash(payload).equals(expected)) {
            throw new IOException("Lumi package " + kind + " hash mismatch");
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] payload)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(payload);
        output.closeEntry();
    }

    private static String commitPath(CommitId id) {
        return "commits/" + id.hex() + ".bin";
    }

    private static String objectPath(ObjectId id) {
        return "objects/" + id.hex() + ".bin";
    }

    private static String previewPath(CommitId id) {
        return "previews/" + id.hex() + ".png";
    }

    @FunctionalInterface
    public interface PayloadReader {
        byte[] read(ObjectId id) throws IOException;
    }

    public interface PayloadConsumer {
        void commit(CommitId id, byte[] payload) throws IOException;
        void object(ObjectId id, byte[] payload) throws IOException;
        default void preview(CommitId id, byte[] png) throws IOException { }
    }
}
