package io.github.luma.domain.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProjectVariantSwitchKeys {

    private static final List<String> DEFAULT_KEYS = List.of(
            "key.keyboard.1",
            "key.keyboard.2",
            "key.keyboard.3",
            "key.keyboard.4",
            "key.keyboard.5",
            "key.keyboard.6",
            "key.keyboard.7",
            "key.keyboard.8",
            "key.keyboard.9",
            "key.keyboard.0"
    );

    private ProjectVariantSwitchKeys() {
    }

    public static String defaultKey(int index) {
        return index >= 0 && index < DEFAULT_KEYS.size() ? DEFAULT_KEYS.get(index) : "";
    }

    public static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    public static List<ProjectVariant> fillMissingDefaults(List<ProjectVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }

        Set<String> used = new HashSet<>();
        for (ProjectVariant variant : variants) {
            String key = variant == null ? "" : variant.switchKey();
            if (key != null && !key.isBlank()) {
                used.add(normalize(key));
            }
        }

        List<ProjectVariant> normalized = new ArrayList<>();
        for (int index = 0; index < variants.size(); index++) {
            ProjectVariant variant = variants.get(index);
            if (variant == null) {
                continue;
            }
            String key = variant.switchKey();
            if (key == null) {
                String defaultKey = defaultKey(index);
                key = defaultKey.isBlank() || used.contains(defaultKey) ? "" : defaultKey;
            }
            key = normalize(key);
            if (!key.isBlank()) {
                used.add(key);
            }
            normalized.add(variant.withSwitchKey(key));
        }
        return List.copyOf(normalized);
    }

    public static List<ProjectVariant> assign(List<ProjectVariant> variants, String variantId, String switchKey) {
        String normalizedKey = normalize(switchKey);
        List<ProjectVariant> updated = new ArrayList<>();
        boolean found = false;
        for (ProjectVariant variant : fillMissingDefaults(variants)) {
            if (variant.id().equals(variantId)) {
                updated.add(variant.withSwitchKey(normalizedKey));
                found = true;
            } else if (!normalizedKey.isBlank() && normalizedKey.equals(normalize(variant.switchKey()))) {
                updated.add(variant.withSwitchKey(""));
            } else {
                updated.add(variant);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Variant not found: " + variantId);
        }
        return List.copyOf(updated);
    }
}
