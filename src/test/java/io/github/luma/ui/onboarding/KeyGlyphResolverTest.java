package io.github.luma.ui.onboarding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KeyGlyphResolverTest {

    @Test
    void resolvesLettersAndDigitsFromMinecraftKeyNames() {
        Assertions.assertEquals("s", KeyGlyphResolver.spriteName("key.keyboard.s"));
        Assertions.assertEquals("7", KeyGlyphResolver.spriteName("key.keyboard.7"));
    }

    @Test
    void resolvesCommonModifierAndNavigationKeys() {
        Assertions.assertEquals("alt", KeyGlyphResolver.spriteName("key.keyboard.left.alt"));
        Assertions.assertEquals("altgr", KeyGlyphResolver.spriteName("key.keyboard.right.alt"));
        Assertions.assertEquals("arrowup", KeyGlyphResolver.spriteName("key.keyboard.up"));
        Assertions.assertEquals("space", KeyGlyphResolver.spriteName("key.keyboard.space"));
    }

    @Test
    void ignoresKeysWithoutBundledSprites() {
        Assertions.assertTrue(KeyGlyphResolver.resolve("key.keyboard.f1").isEmpty());
    }
}
