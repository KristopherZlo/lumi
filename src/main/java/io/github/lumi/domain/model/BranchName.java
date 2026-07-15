package io.github.lumi.domain.model;

import java.util.Objects;

public record BranchName(String value) {
    public BranchName {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Branch name must be 1-256 visible characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
