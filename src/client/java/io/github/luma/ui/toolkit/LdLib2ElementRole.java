package io.github.luma.ui.toolkit;

public record LdLib2ElementRole(String id, String ldLib2Type, String purpose) {

    public LdLib2ElementRole {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Element id is required.");
        }
        if (ldLib2Type == null || ldLib2Type.isBlank()) {
            throw new IllegalArgumentException("LDLib2 element type is required.");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Element purpose is required.");
        }
    }
}
