package io.github.luma.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Timing breakdown for a background version save request.
 */
public record VersionSaveTiming(
        Map<String, Long> durationsMs
) {

    public static final String REQUEST_TOTAL = "Request";
    public static final String RESTORE_INTERRUPTED_DRAFT = "RestoreDraft";
    public static final String LOAD_PERSISTED_DRAFT = "LoadDraft";
    public static final String CONSUME_WORKING_DRAFT = "ConsumeDraft";
    public static final String OPERATION_DRAFT_WRITE = "OperationDraftWrite";
    public static final String RECOVERY_DRAFT_DELETE = "RecoveryDraftDelete";
    public static final String OPERATION_QUEUE = "QueueOperation";
    public static final String BACKGROUND_TOTAL = "Background";
    public static final String SUMMARIZE_CHANGES = "SummarizeChanges";
    public static final String PATCH_PAYLOAD_WRITE = "PatchPayload";
    public static final String PATCH_META_WRITE = "PatchMeta";
    public static final String SNAPSHOT_POLICY = "SnapshotPolicy";
    public static final String ENTITY_CHECKPOINT_CAPTURE = "EntityCheckpoint";
    public static final String SNAPSHOT_PREPARATION = "SnapshotPreparation";
    public static final String SNAPSHOT_CAPTURE = "SnapshotCapture";
    public static final String MANIFEST_WRITE = "ManifestWrite";
    public static final String PREVIEW_QUEUE = "PreviewQueue";
    public static final String OPERATION_DRAFT_DELETE = "OperationDraftDelete";
    public static final String REBASE_WORKING_DRAFT = "RebaseDraft";

    public static final List<String> PHASES = List.of(
            REQUEST_TOTAL,
            RESTORE_INTERRUPTED_DRAFT,
            LOAD_PERSISTED_DRAFT,
            CONSUME_WORKING_DRAFT,
            OPERATION_DRAFT_WRITE,
            RECOVERY_DRAFT_DELETE,
            OPERATION_QUEUE,
            BACKGROUND_TOTAL,
            SUMMARIZE_CHANGES,
            PATCH_PAYLOAD_WRITE,
            PATCH_META_WRITE,
            SNAPSHOT_POLICY,
            ENTITY_CHECKPOINT_CAPTURE,
            SNAPSHOT_PREPARATION,
            SNAPSHOT_CAPTURE,
            MANIFEST_WRITE,
            PREVIEW_QUEUE,
            OPERATION_DRAFT_DELETE,
            REBASE_WORKING_DRAFT
    );

    public VersionSaveTiming {
        durationsMs = durationsMs == null ? Map.of() : Map.copyOf(durationsMs);
    }

    public long durationMs(String phase) {
        if (phase == null || phase.isBlank()) {
            return 0L;
        }
        return this.durationsMs.getOrDefault(phase, 0L);
    }
}
