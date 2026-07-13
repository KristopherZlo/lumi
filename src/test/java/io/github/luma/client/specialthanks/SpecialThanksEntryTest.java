package io.github.luma.client.specialthanks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecialThanksEntryTest {

    @Test
    void entrySupportsOptionalDirectSkinUrl() {
        SpecialThanksEntry entry = new SpecialThanksEntry(
                "Builder",
                "",
                "https://example.com/skin.png",
                "lumi:special-thanks/builder.png",
                "Tester"
        );

        assertEquals("Builder", entry.skinName());
        assertEquals("", entry.profileSkinName());
        assertEquals("https://example.com/skin.png", entry.skinUrl());
        assertEquals("lumi:special-thanks/builder.png", entry.skinAsset());
        assertEquals("Tester", entry.description());
    }

    @Test
    void legacyConstructorKeepsDescriptionAsThirdArgument() {
        SpecialThanksEntry entry = new SpecialThanksEntry("Builder", "SkinNick", "Tester");

        assertEquals("SkinNick", entry.skinName());
        assertEquals("", entry.skinUrl());
        assertEquals("Tester", entry.description());
    }

    @Test
    void remoteEntryInheritsBundledSkinAsset() {
        SpecialThanksEntry remote = new SpecialThanksEntry(
                "Builder",
                "",
                "https://example.com/skin.png",
                "Tester"
        );

        assertEquals(
                "lumi:special-thanks/builder.png",
                remote.withSkinAssetFallback("lumi:special-thanks/builder.png").skinAsset()
        );
    }
}
