package io.github.luma.storage;

import java.nio.file.Path;

/**
 * Canonical path layout for one Luma project on disk.
 *
 * <p>This record centralizes all project-relative file and directory names so
 * that services and repositories do not hardcode storage paths independently.
 */
public record ProjectLayout(Path root) {

    public static ProjectLayout of(Path projectsRoot, String projectName) {
        return new ProjectLayout(projectsRoot.resolve(safeFolderName(projectName) + ".mbp"));
    }

    private static String safeFolderName(String projectName) {
        String normalized = projectName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return normalized.isBlank() ? "project" : normalized;
    }

    public Path projectFile() {
        return this.root.resolve("project.json");
    }

    public Path variantsFile() {
        return this.root.resolve("variants.json");
    }

    public Path versionsDir() {
        return this.root.resolve("versions");
    }

    public Path patchesDir() {
        return this.root.resolve("patches");
    }

    public Path snapshotsDir() {
        return this.root.resolve("snapshots");
    }

    public Path previewsDir() {
        return this.root.resolve("previews");
    }

    public Path recoveryDir() {
        return this.root.resolve("recovery");
    }

    public Path cacheDir() {
        return this.root.resolve("cache");
    }

    public Path locksDir() {
        return this.root.resolve("locks");
    }

    public Path versionFile(String versionId) {
        return this.versionsDir().resolve(versionId + ".json");
    }

    public Path patchFile(String patchId) {
        return this.patchDataFile(patchId);
    }

    public Path patchMetaFile(String patchId) {
        return this.patchesDir().resolve(patchId + ".meta.json");
    }

    public Path patchDataFile(String patchId) {
        return this.patchesDir().resolve(patchId + ".bin.lz4");
    }

    public Path recoveryDraftFile() {
        return this.recoveryBaseFile();
    }

    public Path recoveryJournalFile() {
        return this.recoveryDir().resolve("journal.json");
    }

    public Path previewFile(String versionId) {
        return this.previewsDir().resolve(versionId + ".png");
    }

    public Path snapshotFile(String snapshotId) {
        return this.snapshotsDir().resolve(snapshotId + ".bin.lz4");
    }

    public Path recoveryBaseFile() {
        return this.recoveryDir().resolve("draft.bin.lz4");
    }

    public Path recoveryWalFile() {
        return this.recoveryDir().resolve("draft.wal.lz4");
    }

    public Path recoveryOperationDraftFile() {
        return this.recoveryDir().resolve("operation-draft.bin.lz4");
    }
}
