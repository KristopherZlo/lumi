package io.github.luma.ui.controller;

public record BranchCreationResult(String statusKey, String variantId) {

    public BranchCreationResult {
        statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.operation_failed" : statusKey;
        variantId = variantId == null ? "" : variantId;
    }

    public static BranchCreationResult status(String statusKey) {
        return new BranchCreationResult(statusKey, "");
    }

    public boolean switched() {
        return "luma.status.variant_switched".equals(this.statusKey) && !this.variantId.isBlank();
    }
}
