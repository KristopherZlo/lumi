package io.github.luma.domain.model;

import java.util.LinkedHashSet;
import java.util.List;

public record RecoveryDraftSummary(
        String variantId,
        String baseVersionId,
        String headVersionId,
        int changeCount,
        int touchedChunkCount
) {

    public RecoveryDraftSummary {
        variantId = variantId == null ? "" : variantId;
        baseVersionId = baseVersionId == null ? "" : baseVersionId;
        headVersionId = headVersionId == null ? "" : headVersionId;
        changeCount = Math.max(0, changeCount);
        touchedChunkCount = Math.max(0, touchedChunkCount);
    }

    public static RecoveryDraftSummary from(RecoveryDraft draft, List<ProjectVariant> variants) {
        if (draft == null) {
            return new RecoveryDraftSummary("", "", "", 0, 0);
        }

        List<StoredBlockChange> changes = draft.changes() == null ? List.of() : List.copyOf(draft.changes());
        String variantId = draft.variantId() == null ? "" : draft.variantId();
        String baseVersionId = draft.baseVersionId() == null ? "" : draft.baseVersionId();
        return new RecoveryDraftSummary(
                variantId,
                baseVersionId,
                resolveHeadVersion(variantId, baseVersionId, variants),
                changes.size(),
                touchedChunkCount(changes)
        );
    }

    private static String resolveHeadVersion(String variantId, String baseVersionId, List<ProjectVariant> variants) {
        if (variants != null) {
            for (ProjectVariant variant : variants) {
                if (variant != null && variantId.equals(variant.id()) && variant.headVersionId() != null && !variant.headVersionId().isBlank()) {
                    return variant.headVersionId();
                }
            }
        }
        return baseVersionId;
    }

    private static int touchedChunkCount(List<StoredBlockChange> changes) {
        LinkedHashSet<String> touched = new LinkedHashSet<>();
        for (StoredBlockChange change : changes) {
            if (change == null || change.pos() == null) {
                continue;
            }
            int chunkX = change.pos().x() >> 4;
            int chunkZ = change.pos().z() >> 4;
            touched.add(chunkX + ":" + chunkZ);
        }
        return touched.size();
    }
}
