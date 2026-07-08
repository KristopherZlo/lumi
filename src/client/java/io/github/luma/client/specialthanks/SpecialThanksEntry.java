package io.github.luma.client.specialthanks;

public record SpecialThanksEntry(String displayName, String skinName, String skinUrl, String description) {

    public SpecialThanksEntry(String displayName, String skinName, String description) {
        this(displayName, skinName, "", description);
    }

    public SpecialThanksEntry {
        displayName = clean(displayName);
        skinName = clean(skinName);
        skinUrl = clean(skinUrl);
        description = clean(description);
    }

    public String skinName() {
        return this.skinName.isBlank() ? this.displayName : this.skinName;
    }

    public String profileSkinName() {
        return this.skinName;
    }

    boolean visible() {
        return !this.displayName.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
