package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.PartialRestorePlanPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Mutable state for one preview-gated partial Restore form. */
final class PartialRestoreFormState {
    private final CommitId target;
    private String minX = "";
    private String minY = "";
    private String minZ = "";
    private String maxX = "";
    private String maxY = "";
    private String maxZ = "";
    private boolean outside;
    private boolean selectionSource;
    private UUID pendingRequest;
    private BlockAreaTarget pendingArea;
    private UUID previewToken;
    private int changedSections;
    private long changedBlocks;
    private String error = "";

    PartialRestoreFormState(CommitId target, Optional<BlockBox> initialBounds) {
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(initialBounds, "initialBounds")
                .ifPresent(this::useSelection);
    }

    String minX() { return minX; }
    String minY() { return minY; }
    String minZ() { return minZ; }
    String maxX() { return maxX; }
    String maxY() { return maxY; }
    String maxZ() { return maxZ; }

    void setMinX(String value) { minX = manual(value); }
    void setMinY(String value) { minY = manual(value); }
    void setMinZ(String value) { minZ = manual(value); }
    void setMaxX(String value) { maxX = manual(value); }
    void setMaxY(String value) { maxY = manual(value); }
    void setMaxZ(String value) { maxZ = manual(value); }

    boolean outside() { return outside; }

    void setOutside(boolean outside) {
        if (this.outside != outside) {
            this.outside = outside;
            invalidatePreview();
        }
    }

    boolean selectionSource() { return selectionSource; }

    void useSelection(BlockBox bounds) {
        BlockBox box = Objects.requireNonNull(bounds, "bounds");
        minX = Integer.toString(box.minX());
        minY = Integer.toString(box.minY());
        minZ = Integer.toString(box.minZ());
        maxX = Integer.toString(box.maxX());
        maxY = Integer.toString(box.maxY());
        maxZ = Integer.toString(box.maxZ());
        selectionSource = true;
        invalidatePreview();
    }

    Optional<BlockAreaTarget> area() {
        try {
            return Optional.of(new BlockAreaTarget(new BlockBox(
                    Integer.parseInt(minX), Integer.parseInt(minY),
                    Integer.parseInt(minZ), Integer.parseInt(maxX),
                    Integer.parseInt(maxY), Integer.parseInt(maxZ)), outside));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    void beginPreview(UUID requestId, BlockAreaTarget area) {
        BlockAreaTarget current = area().orElseThrow(
                () -> new IllegalArgumentException(
                        "luma.status.partial_restore_invalid_bounds"));
        if (!current.equals(Objects.requireNonNull(area, "area"))) {
            throw new IllegalArgumentException("Partial Restore bounds changed");
        }
        invalidatePreview();
        pendingRequest = Objects.requireNonNull(requestId, "requestId");
        pendingArea = current;
    }

    boolean accept(PartialRestorePlanPayload result) {
        Objects.requireNonNull(result, "result");
        if (pendingRequest == null || !pendingRequest.equals(result.requestId())
                || !target.equals(result.target())
                || !pendingArea.equals(result.area())) {
            return false;
        }
        pendingRequest = null;
        pendingArea = null;
        error = result.error();
        if (result.succeeded()) {
            previewToken = result.previewToken();
            changedSections = result.changedSections();
            changedBlocks = result.changedBlocks();
        }
        return true;
    }

    boolean previewPending() { return pendingRequest != null; }
    Optional<UUID> previewToken() { return Optional.ofNullable(previewToken); }
    int changedSections() { return changedSections; }
    long changedBlocks() { return changedBlocks; }
    String error() { return error; }

    boolean canApply() {
        return previewToken != null && changedBlocks > 0 && error.isEmpty();
    }

    private String manual(String value) {
        selectionSource = false;
        invalidatePreview();
        return Objects.requireNonNull(value, "value");
    }

    private void invalidatePreview() {
        pendingRequest = null;
        pendingArea = null;
        previewToken = null;
        changedSections = 0;
        changedBlocks = 0;
        error = "";
    }
}
