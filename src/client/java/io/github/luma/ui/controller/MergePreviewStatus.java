package io.github.luma.ui.controller;

import io.github.luma.domain.model.VariantMergePlan;

public record MergePreviewStatus(
        State state,
        VariantMergePlan plan,
        String detail
) {

    public static MergePreviewStatus pending() {
        return new MergePreviewStatus(State.PENDING, null, "");
    }

    public static MergePreviewStatus ready(VariantMergePlan plan) {
        return new MergePreviewStatus(State.READY, plan, "");
    }

    public static MergePreviewStatus failed(String detail) {
        return new MergePreviewStatus(State.FAILED, null, detail == null ? "" : detail);
    }

    public enum State {
        PENDING,
        READY,
        FAILED
    }
}
