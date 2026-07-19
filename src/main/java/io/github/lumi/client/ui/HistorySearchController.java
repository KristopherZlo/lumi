package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Filters the bounded history snapshot without changing its server-defined order. */
final class HistorySearchController {
    List<HistorySnapshotPayload.Version> filter(
            List<HistorySnapshotPayload.Version> versions, String query) {
        Objects.requireNonNull(versions, "versions");
        String normalized = Objects.requireNonNull(query, "query")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.copyOf(versions);
        }
        String[] tokens = normalized.split("\\s+");
        return versions.stream()
                .filter(version -> matches(version, tokens))
                .toList();
    }

    private boolean matches(HistorySnapshotPayload.Version version, String[] tokens) {
        List<String> fields = List.of(
                version.message().toLowerCase(Locale.ROOT),
                version.author().toLowerCase(Locale.ROOT),
                version.id().hex().toLowerCase(Locale.ROOT),
                version.kind().name().toLowerCase(Locale.ROOT),
                String.join(" ", version.tags().values()).toLowerCase(Locale.ROOT));
        for (String token : tokens) {
            if (fields.stream().noneMatch(field ->
                    field.contains(token) || isSubsequence(token, field))) {
                return false;
            }
        }
        return true;
    }

    private boolean isSubsequence(String token, String field) {
        int matched = 0;
        for (int index = 0; index < field.length() && matched < token.length(); index++) {
            if (field.charAt(index) == token.charAt(matched)) {
                matched++;
            }
        }
        return matched == token.length();
    }
}
