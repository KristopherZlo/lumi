package io.github.luma.storage;

import java.nio.file.Path;

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
}
