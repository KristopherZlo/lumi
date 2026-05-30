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
        return StoragePathPolicy.safeFolderName(projectName);
    }

    public Path projectFile() {
        return this.root.resolve("project.json");
    }

    public Path variantsFile() {
        return this.root.resolve("variants.json");
    }

    public Path historyTombstonesFile() {
        return this.root.resolve("history-tombstones.json");
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

    public Path previewRequestsDir() {
        return this.root.resolve("preview-requests");
    }

    public Path recoveryDir() {
        return this.root.resolve("recovery");
    }

    public Path cacheDir() {
        return this.root.resolve("cache");
    }

    public Path contentCacheDir() {
        return this.cacheDir().resolve("content");
    }

    public Path locksDir() {
        return this.root.resolve("locks");
    }

    public Path versionFile(String versionId) {
        return StoragePathPolicy.resolveStorageFile(this.versionsDir(), versionId, ".json", "version id");
    }

    public Path versionIndexFile() {
        return this.versionsDir().resolve("index.json");
    }

    public Path patchMetaFile(String patchId) {
        return StoragePathPolicy.resolveStorageFile(this.patchesDir(), patchId, ".meta.json", "patch id");
    }

    public Path patchDataFile(String patchId) {
        return StoragePathPolicy.resolveStorageFile(this.patchesDir(), patchId, ".bin.lz4", "patch id");
    }

    public Path recoveryDraftFile() {
        return this.recoveryBaseFile();
    }

    public Path recoveryJournalFile() {
        return this.recoveryDir().resolve("journal.json");
    }

    public Path restoreReturnPointFile() {
        return this.recoveryDir().resolve("last-restore-return.json");
    }

    public Path pendingRestoreCompletionFile() {
        return this.recoveryDir().resolve("pending-restore-completion.json");
    }

    public Path previewFile(String versionId) {
        return StoragePathPolicy.resolveStorageFile(this.previewsDir(), versionId, ".png", "preview version id");
    }

    public Path previewRequestFile(String versionId) {
        return StoragePathPolicy.resolveStorageFile(this.previewRequestsDir(), versionId, ".json", "preview request version id");
    }

    public Path snapshotFile(String snapshotId) {
        return StoragePathPolicy.resolveStorageFile(this.snapshotsDir(), snapshotId, ".bin.lz4", "snapshot id");
    }

    public Path contentFile(String sha256) {
        return StoragePathPolicy.resolveStorageFile(this.contentCacheDir(), sha256, ".bin.lz4", "content hash");
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
