package io.github.luma.storage;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Central validation for project storage names that are later converted to paths.
 */
public final class StoragePathPolicy {

    public static final int MAX_STORAGE_ID_LENGTH = 128;
    public static final int MAX_ARCHIVE_PATH_LENGTH = 512;

    private StoragePathPolicy() {
    }

    public static String requireStorageId(String value, String label) {
        String normalized = normalize(value, label);
        if (normalized.length() > MAX_STORAGE_ID_LENGTH) {
            throw new IllegalArgumentException(label + " is too long");
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException(label + " must not contain '..'");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (!isSafeNameChar(current)) {
                throw new IllegalArgumentException(label + " contains unsupported characters");
            }
        }
        return normalized;
    }

    public static String requireOptionalStorageId(String value, String label) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireStorageId(value, label);
    }

    public static String requireFileName(String value, String label) {
        return requireStorageId(value, label);
    }

    public static String requireArchiveFolderName(String value, String label) {
        String normalized = normalize(value, label);
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".mbp")) {
            throw new IllegalArgumentException(label + " must end with .mbp");
        }
        String stem = normalized.substring(0, normalized.length() - ".mbp".length());
        requireStorageId(stem, label);
        return normalized;
    }

    public static Path resolveStorageFile(Path directory, String storageId, String suffix, String label) {
        String id = requireStorageId(storageId, label);
        Path normalizedDirectory = directory.normalize();
        Path target = normalizedDirectory.resolve(id + suffix).normalize();
        if (!target.startsWith(normalizedDirectory)) {
            throw new IllegalArgumentException(label + " escapes " + normalizedDirectory.getFileName());
        }
        return target;
    }

    public static Path requireContainedPath(Path root, Path candidate, String label) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(label + " escapes " + normalizedRoot);
        }
        return normalizedCandidate;
    }

    public static String safeFolderName(String projectName) {
        String source = projectName == null ? "" : projectName.trim();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            builder.append(isSafeNameChar(current) ? current : '_');
        }
        String safe = builder.toString();
        while (safe.contains("..")) {
            safe = safe.replace("..", ".");
        }
        safe = trimDotsAndUnderscores(safe);
        return safe.isBlank() ? "project" : safe;
    }

    public static String safeArchiveFolderName(String projectFolderName) {
        String source = projectFolderName == null ? "" : projectFolderName.trim();
        String lower = source.toLowerCase(Locale.ROOT);
        String stem = lower.endsWith(".mbp") ? source.substring(0, source.length() - ".mbp".length()) : source;
        return safeFolderName(stem) + ".mbp";
    }

    private static String normalize(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        String normalized = value.trim();
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(label + " must be a file name, not a path");
        }
        if (Path.of(normalized).isAbsolute()) {
            throw new IllegalArgumentException(label + " must be relative");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException(label + " contains control characters");
            }
        }
        return normalized;
    }

    private static boolean isSafeNameChar(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '.'
                || value == '_'
                || value == '-';
    }

    private static String trimDotsAndUnderscores(String value) {
        String result = value;
        while (!result.isEmpty() && (result.charAt(0) == '.' || result.charAt(0) == '_')) {
            result = result.substring(1);
        }
        while (!result.isEmpty() && (result.charAt(result.length() - 1) == '.' || result.charAt(result.length() - 1) == '_')) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
