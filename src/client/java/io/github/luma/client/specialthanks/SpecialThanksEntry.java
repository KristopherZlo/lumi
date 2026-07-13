package io.github.luma.client.specialthanks;

public record SpecialThanksEntry(
        String displayName,
        String skinName,
        String skinUrl,
        String skinAsset,
        String description
) {

    public SpecialThanksEntry(String displayName, String skinName, String skinUrl, String description) {
        this(displayName, skinName, skinUrl, "", description);
    }

    public SpecialThanksEntry(String displayName, String skinName, String description) {
        this(displayName, skinName, "", "", description);
    }

    public SpecialThanksEntry {
        displayName = clean(displayName);
        skinName = clean(skinName);
        skinUrl = clean(skinUrl);
        skinAsset = clean(skinAsset);
        description = clean(description);
    }

    public String skinName() {
        return this.skinName.isBlank() ? this.displayName : this.skinName;
    }

    public String profileSkinName() {
        return this.skinName;
    }

    SpecialThanksEntry withSkinAssetFallback(String fallback) {
        if (!this.skinAsset.isBlank()) {
            return this;
        }
        return new SpecialThanksEntry(
                this.displayName,
                this.skinName,
                this.skinUrl,
                fallback,
                this.description
        );
    }

    boolean visible() {
        return !this.displayName.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
