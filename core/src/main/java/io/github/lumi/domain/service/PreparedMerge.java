package io.github.lumi.domain.service;

import java.util.Objects;

/** Immutable merge preview retained between conflict confirmation and apply. */
public record PreparedMerge(MergeService.Request request, MergeService.Result result) {
    public PreparedMerge {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
    }
}
