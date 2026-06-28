package io.github.luma.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProjectVersionTags {

    public static final String METADATA_KEY = "luma.tags";

    private ProjectVersionTags() {
    }

    public static List<String> from(ProjectVersion version) {
        if (version == null || version.sourceInfo() == null || version.sourceInfo().metadata() == null) {
            return List.of();
        }
        return parse(version.sourceInfo().metadata().get(METADATA_KEY));
    }

    public static List<String> parse(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String rawTag : rawTags.split(",")) {
            String tag = normalize(rawTag);
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    public static String serialize(List<String> tags) {
        return String.join(", ", normalize(tags));
    }

    public static ProjectVersion withTags(ProjectVersion version, List<String> tags) {
        ExternalSourceInfo sourceInfo = version.sourceInfo() == null ? ExternalSourceInfo.manual() : version.sourceInfo();
        Map<String, String> metadata = new LinkedHashMap<>(sourceInfo.metadata() == null ? Map.of() : sourceInfo.metadata());
        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            metadata.remove(METADATA_KEY);
        } else {
            metadata.put(METADATA_KEY, serialize(normalized));
        }
        return new ProjectVersion(
                version.id(),
                version.projectId(),
                version.variantId(),
                version.parentVersionId(),
                version.snapshotId(),
                version.entityCheckpointId(),
                version.patchIds(),
                version.versionKind(),
                version.author(),
                version.message(),
                version.stats(),
                version.preview(),
                ExternalSourceInfo.external(
                        sourceInfo.tool(),
                        sourceInfo.operationType(),
                        sourceInfo.operationLabel(),
                        sourceInfo.actor(),
                        sourceInfo.sourceBounds(),
                        sourceInfo.usedClipboard(),
                        sourceInfo.usedSelection(),
                        metadata
                ),
                version.createdAt()
        );
    }

    private static List<String> normalize(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            String tag = normalize(rawTag);
            if (!tag.isBlank()) {
                normalized.add(tag);
            }
        }
        return new ArrayList<>(normalized);
    }

    private static String normalize(String rawTag) {
        return rawTag == null
                ? ""
                : rawTag.trim()
                        .replaceFirst("^#+", "")
                        .toLowerCase(Locale.ROOT);
    }
}
