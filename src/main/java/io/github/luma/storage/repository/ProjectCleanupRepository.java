package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectCleanupCandidate;
import io.github.luma.domain.model.ProjectCleanupPolicy;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProjectCleanupRepository {

    private static final String REASON_UNREFERENCED_SNAPSHOT = "unreferenced snapshot";
    private static final String REASON_ORPHANED_PREVIEW = "orphaned preview";
    private static final String REASON_ORPHANED_CACHE = "orphaned cache";
    private static final String REASON_STALE_OPERATION_DRAFT = "stale operation draft";

    public List<ProjectCleanupCandidate> inspect(ProjectLayout layout, ProjectCleanupPolicy policy) throws IOException {
        List<ProjectCleanupCandidate> candidates = new ArrayList<>();
        this.collectSnapshots(layout, policy, candidates);
        this.collectPreviews(layout, policy, candidates);
        this.collectCache(layout, candidates);
        if (policy.deleteOperationDraft() && Files.exists(layout.recoveryOperationDraftFile())) {
            candidates.add(this.candidate(layout, layout.recoveryOperationDraftFile(), REASON_STALE_OPERATION_DRAFT));
        }
        candidates.sort(Comparator.comparing(ProjectCleanupCandidate::relativePath));
        return List.copyOf(candidates);
    }

    public List<ProjectCleanupCandidate> apply(ProjectLayout layout, ProjectCleanupPolicy policy) throws IOException {
        List<ProjectCleanupCandidate> candidates = this.inspect(layout, policy);
        for (ProjectCleanupCandidate candidate : candidates) {
            Files.deleteIfExists(layout.root().resolve(candidate.relativePath()));
        }
        this.deleteEmptyDirectories(layout.cacheDir(), Set.of(layout.cacheDir(), layout.cacheDir().resolve("baseline-chunks")));
        return candidates;
    }

    private void collectSnapshots(ProjectLayout layout, ProjectCleanupPolicy policy, List<ProjectCleanupCandidate> candidates) throws IOException {
        if (!Files.exists(layout.snapshotsDir())) {
            return;
        }
        try (var stream = Files.list(layout.snapshotsDir())) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!policy.referencedSnapshotFiles().contains(file.getFileName().toString())) {
                    candidates.add(this.candidate(layout, file, REASON_UNREFERENCED_SNAPSHOT));
                }
            }
        }
    }

    private void collectPreviews(ProjectLayout layout, ProjectCleanupPolicy policy, List<ProjectCleanupCandidate> candidates) throws IOException {
        if (!Files.exists(layout.previewsDir())) {
            return;
        }
        try (var stream = Files.list(layout.previewsDir())) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!policy.referencedPreviewFiles().contains(file.getFileName().toString())) {
                    candidates.add(this.candidate(layout, file, REASON_ORPHANED_PREVIEW));
                }
            }
        }
    }

    private void collectCache(ProjectLayout layout, List<ProjectCleanupCandidate> candidates) throws IOException {
        if (!Files.exists(layout.cacheDir())) {
            return;
        }
        Path protectedRoot = layout.cacheDir().resolve("baseline-chunks").normalize();
        try (var stream = Files.walk(layout.cacheDir())) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (file.normalize().startsWith(protectedRoot)) {
                    continue;
                }
                candidates.add(this.candidate(layout, file, REASON_ORPHANED_CACHE));
            }
        }
    }

    private void deleteEmptyDirectories(Path root, Set<Path> protectedDirectories) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Set<Path> normalizedProtected = new HashSet<>();
        for (Path protectedDirectory : protectedDirectories) {
            normalizedProtected.add(protectedDirectory.normalize());
        }
        try (var stream = Files.walk(root)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (normalizedProtected.contains(directory.normalize())) {
                    continue;
                }
                try (var children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        }
    }

    private ProjectCleanupCandidate candidate(ProjectLayout layout, Path file, String reason) throws IOException {
        return new ProjectCleanupCandidate(
                layout.root().relativize(file).toString().replace('\\', '/'),
                reason,
                Files.size(file)
        );
    }
}
