package io.github.luma.ui.controller;

record MergePreviewKey(
        String targetProjectName,
        String sourceProjectName,
        String sourceVariantId,
        String targetVariantId
) {
}
