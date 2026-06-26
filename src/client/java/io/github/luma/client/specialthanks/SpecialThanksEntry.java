package io.github.luma.client.specialthanks;

public record SpecialThanksEntry(String displayName, String skinName, String description) {

    public SpecialThanksEntry {
        displayName = clean(displayName);
        skinName = clean(skinName);
        description = clean(description);
    }

    public String skinName() {
        return this.skinName.isBlank() ? this.displayName : this.skinName;
    }

    boolean visible() {
        return !this.displayName.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
