package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectArchiveEntry;
import io.github.luma.domain.model.ProjectArchiveManifest;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ProjectRepository projectRepository = new ProjectRepository();

    public ProjectArchiveManifest exportArchive(
            ProjectLayout layout,
            BuildProject project,
            Path archiveFile,
            boolean includePreviews
    ) throws IOException {
        List<ProjectArchiveEntry> entries = this.collectEntries(layout, includePreviews);
        ProjectArchiveManifest manifest = new ProjectArchiveManifest(
                ProjectArchiveManifest.CURRENT_SCHEMA_VERSION,
                project.name(),
                layout.root().getFileName().toString(),
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
            try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
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
                return manifest;
            } catch (JsonSyntaxException exception) {
                throw new IOException("Archive manifest is malformed", exception);
            }
        }
    }

    public ProjectLayout importArchive(Path projectsRoot, Path archiveFile) throws IOException {
        ProjectArchiveManifest manifest = this.loadManifest(archiveFile);
        Path targetRoot = projectsRoot.resolve(manifest.projectFolderName());
        if (Files.exists(targetRoot)) {
            throw new IOException("Project storage already exists: " + manifest.projectFolderName());
        }

        Files.createDirectories(projectsRoot);
        Path tempRoot = Files.createTempDirectory(projectsRoot, manifest.projectFolderName() + ".import-");
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
                Path target = tempRoot.resolve(this.projectRelativePath(entry.path())).normalize();
                if (!target.startsWith(tempRoot)) {
                    throw new IOException("Archive entry escapes project root: " + entry.path());
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    Files.copy(input, target);
                }
            }
        } catch (Exception exception) {
            this.deleteTree(tempRoot);
            throw exception;
        }

        ProjectLayout importedLayout = new ProjectLayout(tempRoot);
        this.projectRepository.initializeLayout(importedLayout);
        Optional<BuildProject> importedProject = this.projectRepository.load(importedLayout);
        if (importedProject.isEmpty()) {
            this.deleteTree(tempRoot);
            throw new IOException("Imported archive is missing project metadata");
        }

        try {
            Files.move(tempRoot, targetRoot);
        } catch (IOException exception) {
            this.deleteTree(tempRoot);
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

    private void collectDirectoryEntries(Path directory, String prefix, List<ProjectArchiveEntry> entries) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                entries.add(this.optionalEntry(file, prefix + relative));
            }
        }
    }

    private ProjectArchiveEntry requiredEntry(Path file, String archivePath) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Missing required project file " + file.getFileName());
        }
        return new ProjectArchiveEntry(archivePath, Files.size(file), false);
    }

    private ProjectArchiveEntry optionalEntry(Path file, String archivePath) throws IOException {
        return new ProjectArchiveEntry(archivePath, Files.size(file), true);
    }

    private Path resolveSource(ProjectLayout layout, String archivePath) {
        return layout.root().resolve(this.projectRelativePath(archivePath));
    }

    private String projectRelativePath(String archivePath) {
        return archivePath.substring(PROJECT_PREFIX.length());
    }

    private void validateArchiveEntry(String archivePath) throws IOException {
        if (!archivePath.startsWith(PROJECT_PREFIX) || archivePath.contains("..") || archivePath.startsWith("/")) {
            throw new IOException("Unsupported archive entry " + archivePath);
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

    private Map<String, ZipEntry> indexZipEntries(ZipFile zip) {
        Map<String, ZipEntry> entries = new HashMap<>();
        zip.stream().forEach(entry -> entries.put(entry.getName(), entry));
        return entries;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
