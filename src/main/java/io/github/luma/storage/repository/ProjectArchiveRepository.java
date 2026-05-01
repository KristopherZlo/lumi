package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectArchiveEntry;
import io.github.luma.domain.model.ProjectArchiveManifest;
import io.github.luma.domain.model.ProjectArchiveScope;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.StoragePathPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ProjectArchiveRepository {

    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String PROJECT_PREFIX = "project/";
    private static final String BASELINE_PREFIX = PROJECT_PREFIX + "cache/baseline-chunks/";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 20_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L;
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();

    public ProjectArchiveManifest exportArchive(
            ProjectLayout layout,
            BuildProject project,
            Path archiveFile,
            boolean includePreviews
    ) throws IOException {
        List<ProjectArchiveEntry> entries = this.collectEntries(layout, includePreviews);
        ProjectArchiveManifest manifest = new ProjectArchiveManifest(
                ProjectArchiveManifest.CURRENT_SCHEMA_VERSION,
                ProjectArchiveScope.project(),
                project.name(),
                StoragePathPolicy.safeArchiveFolderName(layout.root().getFileName().toString()),
                project.id().toString(),
                Instant.now(),
                includePreviews,
                entries
        );

        StorageIo.writeAtomically(archiveFile, output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                OutputStreamWriter writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                GsonProvider.compactGson().toJson(manifest, writer);
                writer.flush();
                zip.closeEntry();
                for (ProjectArchiveEntry entry : entries) {
                    this.validateArchiveEntry(entry.path());
                    zip.putNextEntry(new ZipEntry(entry.path()));
                    Files.copy(this.resolveSource(layout, entry.path()), zip);
                    zip.closeEntry();
                }
            }
        });
        return manifest;
    }

    public ProjectArchiveManifest exportVariantArchive(
            ProjectLayout layout,
            BuildProject project,
            ProjectVariant variant,
            List<ProjectVersion> versions,
            Path archiveFile,
            boolean includePreviews
    ) throws IOException {
        List<ProjectArchiveEntry> entries = this.collectVariantEntries(layout, variant, versions, includePreviews);
        ProjectArchiveManifest manifest = new ProjectArchiveManifest(
                ProjectArchiveManifest.CURRENT_SCHEMA_VERSION,
                ProjectArchiveScope.variant(variant),
                project.name(),
                StoragePathPolicy.safeArchiveFolderName(layout.root().getFileName().toString()),
                project.id().toString(),
                Instant.now(),
                includePreviews,
                entries
        );

        StorageIo.writeAtomically(archiveFile, output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                OutputStreamWriter writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                GsonProvider.compactGson().toJson(manifest, writer);
                writer.flush();
                zip.closeEntry();
                for (ProjectArchiveEntry entry : entries) {
                    this.validateArchiveEntry(entry.path());
                    zip.putNextEntry(new ZipEntry(entry.path()));
                    Files.copy(this.resolveSource(layout, entry.path()), zip);
                    zip.closeEntry();
                }
            }
        });
        return manifest;
    }

    public ProjectArchiveManifest loadManifest(Path archiveFile) throws IOException {
        try (ZipFile zip = new ZipFile(archiveFile.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                throw new IOException("Archive is missing manifest.json");
            }
            this.validateKnownEntrySize(entry, MAX_MANIFEST_BYTES, "archive manifest");
            byte[] manifestBytes;
            try (InputStream input = zip.getInputStream(entry)) {
                manifestBytes = StorageIo.readAllBytesBounded(input, MAX_MANIFEST_BYTES, "archive manifest");
            }
            try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(manifestBytes), StandardCharsets.UTF_8)) {
                ProjectArchiveManifest manifest = GsonProvider.gson().fromJson(reader, ProjectArchiveManifest.class);
                if (manifest == null) {
                    throw new IOException("Archive manifest is empty");
                }
                if (manifest.schemaVersion() > ProjectArchiveManifest.CURRENT_SCHEMA_VERSION) {
                    throw new IOException("Unsupported archive schema " + manifest.schemaVersion());
                }
                if (manifest.entries() == null) {
                    throw new IOException("Archive manifest is missing entry list");
                }
                if (manifest.entries().size() > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive has too many entries");
                }
                StoragePathPolicy.requireArchiveFolderName(manifest.projectFolderName(), "archive project folder");
                return manifest;
            } catch (JsonSyntaxException exception) {
                throw new IOException("Archive manifest is malformed", exception);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Archive manifest is invalid", exception);
            }
        }
    }

    public ProjectLayout importArchive(Path projectsRoot, Path archiveFile) throws IOException {
        ProjectArchiveManifest manifest = this.loadManifest(archiveFile);
        String projectFolderName = StoragePathPolicy.requireArchiveFolderName(manifest.projectFolderName(), "archive project folder");
        Path normalizedProjectsRoot = projectsRoot.toAbsolutePath().normalize();
        Path targetRoot = normalizedProjectsRoot.resolve(projectFolderName).normalize();
        try {
            StoragePathPolicy.requireContainedPath(normalizedProjectsRoot, targetRoot, "archive project folder");
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        if (Files.exists(targetRoot)) {
            throw new IOException("Project storage already exists: " + projectFolderName);
        }

        Files.createDirectories(normalizedProjectsRoot);
        Path tempRoot = Files.createTempDirectory(normalizedProjectsRoot, projectFolderName + ".import-");
        long totalCopiedBytes = 0L;
        try (ZipFile zip = new ZipFile(archiveFile.toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> zipEntries = this.indexZipEntries(zip);
            for (ProjectArchiveEntry entry : manifest.entries()) {
                this.validateArchiveEntry(entry.path());
                ZipEntry zipEntry = zipEntries.get(entry.path());
                if (zipEntry == null) {
                    if (entry.optional()) {
                        continue;
                    }
                    throw new IOException("Archive is missing " + entry.path());
                }
                this.validateArchivePayloadEntry(entry, zipEntry);
                Path target = tempRoot.resolve(this.projectRelativePath(entry.path())).normalize();
                if (!target.startsWith(tempRoot)) {
                    throw new IOException("Archive entry escapes project root: " + entry.path());
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    long copied = this.copyBounded(input, target, entry);
                    totalCopiedBytes = Math.addExact(totalCopiedBytes, copied);
                    if (totalCopiedBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("Archive exceeds the maximum unpacked size");
                    }
                }
            }
        } catch (ArithmeticException exception) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw new IOException("Archive unpacked size overflow", exception);
        } catch (Exception exception) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw exception;
        }

        ProjectLayout importedLayout = new ProjectLayout(tempRoot);
        this.projectRepository.initializeLayout(importedLayout);
        Optional<BuildProject> importedProject = this.projectRepository.load(importedLayout);
        if (importedProject.isEmpty()) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw new IOException("Imported archive is missing project metadata");
        }
        try {
            this.validateImportedStorage(importedLayout);
        } catch (IOException exception) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw exception;
        } catch (RuntimeException exception) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw new IOException("Imported archive storage is invalid", exception);
        }

        try {
            Files.move(tempRoot, targetRoot);
        } catch (IOException exception) {
            this.deleteTree(tempRoot, normalizedProjectsRoot);
            throw exception;
        }
        return new ProjectLayout(targetRoot);
    }

    private List<ProjectArchiveEntry> collectEntries(ProjectLayout layout, boolean includePreviews) throws IOException {
        List<ProjectArchiveEntry> entries = new ArrayList<>();
        entries.add(this.requiredEntry(layout.projectFile(), PROJECT_PREFIX + "project.json"));
        entries.add(this.requiredEntry(layout.variantsFile(), PROJECT_PREFIX + "variants.json"));
        this.collectDirectoryEntries(layout.versionsDir(), PROJECT_PREFIX + "versions/", entries);
        this.collectDirectoryEntries(layout.patchesDir(), PROJECT_PREFIX + "patches/", entries);
        this.collectDirectoryEntries(layout.snapshotsDir(), PROJECT_PREFIX + "snapshots/", entries);
        this.collectDirectoryEntries(layout.cacheDir().resolve("baseline-chunks"), BASELINE_PREFIX, entries);
        Path journalFile = layout.recoveryJournalFile();
        if (Files.exists(journalFile)) {
            entries.add(this.optionalEntry(journalFile, PROJECT_PREFIX + "recovery/journal.json"));
        }
        if (includePreviews) {
            this.collectDirectoryEntries(layout.previewsDir(), PROJECT_PREFIX + "previews/", entries);
        }
        return List.copyOf(entries);
    }

    private List<ProjectArchiveEntry> collectVariantEntries(
            ProjectLayout layout,
            ProjectVariant variant,
            List<ProjectVersion> versions,
            boolean includePreviews
    ) throws IOException {
        Map<String, ProjectVersion> versionMap = new LinkedHashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        LinkedHashMap<String, ProjectArchiveEntry> entries = new LinkedHashMap<>();
        this.putEntry(entries, this.requiredEntry(layout.projectFile(), PROJECT_PREFIX + "project.json"));
        this.putEntry(entries, this.requiredEntry(layout.variantsFile(), PROJECT_PREFIX + "variants.json"));
        for (ProjectVersion version : this.lineageVersions(versionMap, variant.headVersionId())) {
            this.putEntry(entries, this.requiredEntry(layout.versionFile(version.id()), PROJECT_PREFIX + "versions/" + version.id() + ".json"));
            for (String patchId : version.patchIds()) {
                this.putEntry(entries, this.requiredEntry(layout.patchMetaFile(patchId), PROJECT_PREFIX + "patches/" + patchId + ".meta.json"));
                this.putEntry(entries, this.requiredEntry(layout.patchDataFile(patchId), PROJECT_PREFIX + "patches/" + patchId + ".bin.lz4"));
            }
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                this.putEntry(entries, this.requiredEntry(layout.snapshotFile(version.snapshotId()), PROJECT_PREFIX + "snapshots/" + version.snapshotId() + ".bin.lz4"));
            }
            if (includePreviews && version.preview() != null && version.preview().fileName() != null && !version.preview().fileName().isBlank()) {
                Path previewFile = layout.previewFile(version.id());
                if (Files.exists(previewFile)) {
                    this.putEntry(entries, this.optionalEntry(previewFile, PROJECT_PREFIX + "previews/" + previewFile.getFileName()));
                }
            }
        }
        this.collectDirectoryEntries(layout.cacheDir().resolve("baseline-chunks"), BASELINE_PREFIX, entries);
        Path journalFile = layout.recoveryJournalFile();
        if (Files.exists(journalFile)) {
            this.putEntry(entries, this.optionalEntry(journalFile, PROJECT_PREFIX + "recovery/journal.json"));
        }
        return List.copyOf(entries.values());
    }

    private void collectDirectoryEntries(Path directory, String prefix, List<ProjectArchiveEntry> entries) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        this.rejectSymbolicLink(directory);
        try (var stream = Files.walk(directory)) {
            for (Path file : stream.sorted().toList()) {
                this.rejectSymbolicLink(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = directory.relativize(file).toString().replace('\\', '/');
                entries.add(this.optionalEntry(file, prefix + relative));
            }
        }
    }

    private void collectDirectoryEntries(Path directory, String prefix, Map<String, ProjectArchiveEntry> entries) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        this.rejectSymbolicLink(directory);
        try (var stream = Files.walk(directory)) {
            for (Path file : stream.sorted().toList()) {
                this.rejectSymbolicLink(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = directory.relativize(file).toString().replace('\\', '/');
                this.putEntry(entries, this.optionalEntry(file, prefix + relative));
            }
        }
    }

    private ProjectArchiveEntry requiredEntry(Path file, String archivePath) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing required project file " + file.getFileName());
        }
        this.rejectSymbolicLink(file);
        return new ProjectArchiveEntry(archivePath, Files.size(file), false);
    }

    private ProjectArchiveEntry optionalEntry(Path file, String archivePath) throws IOException {
        this.rejectSymbolicLink(file);
        return new ProjectArchiveEntry(archivePath, Files.size(file), true);
    }

    private Path resolveSource(ProjectLayout layout, String archivePath) throws IOException {
        Path root = layout.root().toAbsolutePath().normalize();
        Path source = root.resolve(this.projectRelativePath(archivePath)).normalize();
        try {
            StoragePathPolicy.requireContainedPath(root, source, "archive source");
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        this.rejectSymbolicLink(source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive source is not a regular file: " + archivePath);
        }
        return source;
    }

    private void putEntry(Map<String, ProjectArchiveEntry> entries, ProjectArchiveEntry entry) {
        entries.putIfAbsent(entry.path(), entry);
    }

    private String projectRelativePath(String archivePath) {
        return archivePath.substring(PROJECT_PREFIX.length());
    }

    private void validateArchiveEntry(String archivePath) throws IOException {
        if (archivePath == null || archivePath.isBlank() || archivePath.length() > StoragePathPolicy.MAX_ARCHIVE_PATH_LENGTH) {
            throw new IOException("Unsupported archive entry " + archivePath);
        }
        if (!archivePath.startsWith(PROJECT_PREFIX)
                || archivePath.startsWith("/")
                || archivePath.startsWith("\\")
                || archivePath.contains("\\")
                || archivePath.contains("//")) {
            throw new IOException("Unsupported archive entry " + archivePath);
        }
        for (String segment : archivePath.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.contains("..")) {
                throw new IOException("Unsupported archive entry " + archivePath);
            }
        }
        if (archivePath.equals(PROJECT_PREFIX + "project.json")
                || archivePath.equals(PROJECT_PREFIX + "variants.json")
                || archivePath.equals(PROJECT_PREFIX + "recovery/journal.json")) {
            return;
        }
        if (archivePath.startsWith(PROJECT_PREFIX + "versions/")
                || archivePath.startsWith(PROJECT_PREFIX + "patches/")
                || archivePath.startsWith(PROJECT_PREFIX + "snapshots/")
                || archivePath.startsWith(PROJECT_PREFIX + "previews/")
                || archivePath.startsWith(BASELINE_PREFIX)) {
            return;
        }
        throw new IOException("Archive entry is not allowed for import: " + archivePath);
    }

    private void validateArchivePayloadEntry(ProjectArchiveEntry entry, ZipEntry zipEntry) throws IOException {
        if (zipEntry.isDirectory()) {
            throw new IOException("Archive entry is a directory: " + entry.path());
        }
        if (entry.size() > MAX_ENTRY_BYTES) {
            throw new IOException("Archive entry is too large: " + entry.path());
        }
        if (entry.size() < 0L) {
            throw new IOException("Archive entry has a negative size: " + entry.path());
        }
        this.validateKnownEntrySize(zipEntry, MAX_ENTRY_BYTES, entry.path());
        if (zipEntry.getSize() >= 0L && zipEntry.getSize() != entry.size()) {
            throw new IOException("Archive entry size mismatch: " + entry.path());
        }
    }

    private void validateKnownEntrySize(ZipEntry entry, long maxBytes, String label) throws IOException {
        long size = entry.getSize();
        if (size > maxBytes) {
            throw new IOException(label + " is too large");
        }
    }

    private long copyBounded(InputStream input, Path target, ProjectArchiveEntry entry) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException("Archive entry is too large: " + entry.path());
                }
                output.write(buffer, 0, read);
            }
        }
        if (entry.size() >= 0L && total != entry.size()) {
            throw new IOException("Archive entry size mismatch: " + entry.path());
        }
        return total;
    }

    private void validateImportedStorage(ProjectLayout layout) throws IOException {
        this.variantRepository.loadAll(layout);
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        for (ProjectVersion version : versions) {
            layout.versionFile(version.id());
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                Path snapshot = layout.snapshotFile(version.snapshotId());
                if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Imported archive is missing snapshot " + version.snapshotId());
                }
            }
            if (version.preview() != null && version.preview().fileName() != null && !version.preview().fileName().isBlank()) {
                StoragePathPolicy.requireFileName(version.preview().fileName(), "preview file");
                layout.previewFile(version.id());
            }
            for (String patchId : version.patchIds()) {
                Path metadataFile = layout.patchMetaFile(patchId);
                Path dataFile = layout.patchDataFile(patchId);
                if (!Files.exists(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Imported archive is missing patch metadata " + patchId);
                }
                if (!Files.exists(dataFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Imported archive is missing patch payload " + patchId);
                }
                this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IOException("Imported archive is missing patch metadata " + patchId));
            }
        }
    }

    private void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are not supported in project archives: " + path.getFileName());
        }
    }

    private Map<String, ZipEntry> indexZipEntries(ZipFile zip) {
        Map<String, ZipEntry> entries = new HashMap<>();
        zip.stream().forEach(entry -> entries.put(entry.getName(), entry));
        return entries;
    }

    private List<ProjectVersion> lineageVersions(Map<String, ProjectVersion> versionMap, String headVersionId) {
        List<ProjectVersion> reversed = new ArrayList<>();
        ProjectVersion cursor = headVersionId == null || headVersionId.isBlank() ? null : versionMap.get(headVersionId);
        while (cursor != null) {
            reversed.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        reversed.sort(Comparator.comparing(ProjectVersion::createdAt));
        return List.copyOf(reversed);
    }

    private void deleteTree(Path root, Path containmentRoot) throws IOException {
        Path normalizedRoot;
        try {
            normalizedRoot = StoragePathPolicy.requireContainedPath(containmentRoot, root, "delete root");
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(normalizedRoot)) {
            Files.deleteIfExists(normalizedRoot);
            return;
        }
        try (var stream = Files.walk(normalizedRoot)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
