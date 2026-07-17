package io.github.lumi.client.specialthanks;

/** One bundled credit and the optional sources for its player skin. */
public record SpecialThanksEntry(
        String displayName,
        String skinName,
        String skinUrl,
        String skinAsset,
        String description) {
    public SpecialThanksEntry {
        displayName = clean(displayName);
        skinName = clean(skinName);
        skinUrl = clean(skinUrl);
        skinAsset = clean(skinAsset);
        description = clean(description);
    }

    @Override
    public String skinName() {
        return skinName.isBlank() ? displayName : skinName;
    }

    public String profileSkinName() {
        return skinName;
    }

    boolean visible() {
        return !displayName.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
