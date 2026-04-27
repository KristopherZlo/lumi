package io.github.luma.ui;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ProjectUiSupport {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private ProjectUiSupport() {
    }

    public static ProjectVariant variantFor(List<ProjectVariant> variants, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return null;
        }
        for (ProjectVariant variant : variants) {
            if (variantId.equals(variant.id())) {
                return variant;
            }
        }
        return null;
    }

    public static ProjectVersion versionFor(List<ProjectVersion> versions, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        for (ProjectVersion version : versions) {
            if (versionId.equals(version.id())) {
                return version;
            }
        }
        return null;
    }

    public static ProjectVersion activeHead(BuildProject project, List<ProjectVariant> variants, List<ProjectVersion> versions) {
        if (project == null) {
            return null;
        }

        ProjectVariant variant = variantFor(variants, project.activeVariantId());
        return variant == null ? null : versionFor(versions, variant.headVersionId());
    }

    public static boolean isVariantHead(List<ProjectVariant> variants, ProjectVersion version) {
        if (version == null) {
            return false;
        }

        ProjectVariant variant = variantFor(variants, version.variantId());
        return variant != null
                && variant.headVersionId() != null
                && variant.headVersionId().equals(version.id());
    }

    public static String displayVariantName(ProjectVariant variant) {
        if (variant == null) {
            return "";
        }
        return safeText(variant.name()).isBlank() ? variant.id() : variant.name();
    }

    public static String displayVariantName(List<ProjectVariant> variants, String variantId) {
        ProjectVariant variant = variantFor(variants, variantId);
        return variant == null ? safeText(variantId) : displayVariantName(variant);
    }

    public static String displayMessage(ProjectVersion version) {
        if (version == null) {
            return "";
        }
        return safeText(version.message()).isBlank() ? version.id() : version.message();
    }

    public static String formatTimestamp(Instant timestamp) {
        return timestamp == null ? "" : TIMESTAMP_FORMATTER.format(timestamp);
    }

    public static String safeText(String value) {
        return value == null ? "" : value;
    }

    public static String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }

    public static String versionKindKey(VersionKind versionKind) {
        return switch (versionKind) {
            case INITIAL -> "luma.version_kind.initial";
            case MANUAL -> "luma.version_kind.manual";
            case RECOVERY -> "luma.version_kind.recovery";
            case RESTORE -> "luma.version_kind.restore";
            case PARTIAL_RESTORE -> "luma.version_kind.partial_restore";
            case LEGACY -> "luma.version_kind.legacy";
            case WORLD_ROOT -> "luma.version_kind.world_root";
        };
    }

    public static int changeCount(VersionDiff diff, ChangeType type) {
        if (diff == null) {
            return 0;
        }

        int count = 0;
        for (var entry : diff.changedBlocks()) {
            if (entry.changeType() == type) {
                count += 1;
            }
        }
        return count;
    }
}
