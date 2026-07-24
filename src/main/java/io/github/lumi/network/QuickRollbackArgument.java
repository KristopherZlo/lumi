package io.github.lumi.network;

import java.util.Objects;

/** Whole-workspace Quick Restore intent. */
public record QuickRollbackArgument() {
    private static final String WHOLE_SCOPE = "whole";

    public String encode() {
        return WHOLE_SCOPE;
    }

    public static QuickRollbackArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (!WHOLE_SCOPE.equals(encoded)) {
            throw new IllegalArgumentException("Invalid Quick Restore scope");
        }
        return new QuickRollbackArgument();
    }
}
