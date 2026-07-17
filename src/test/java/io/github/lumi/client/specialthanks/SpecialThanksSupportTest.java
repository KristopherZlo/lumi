package io.github.lumi.client.specialthanks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SpecialThanksSupportTest {
    @Test
    void entryCleansFieldsWithoutTurningDisplayNameIntoAProfileLookup() {
        var entry = new SpecialThanksEntry(
                " Builder ", " ", " https://example.com/skin.png ",
                " lumi:special-thanks/builder.png ", " Tester ");

        assertEquals("Builder", entry.displayName());
        assertEquals("Builder", entry.skinName());
        assertEquals("", entry.profileSkinName());
        assertEquals("https://example.com/skin.png", entry.skinUrl());
        assertEquals("lumi:special-thanks/builder.png", entry.skinAsset());
        assertEquals("Tester", entry.description());
    }

    @Test
    void bundledCatalogLoadsBothVisibleCreditsAndBoundsExtraEntries() {
        var source = new SpecialThanksCatalogSource();

        assertEquals(
                java.util.List.of("ImZlo", "Nayakochii"),
                source.loadBundled().stream()
                        .map(SpecialThanksEntry::displayName).toList());
        assertEquals(2, source.parse("""
                {"schema":2,"people":[
                  {"displayName":"A"},{"displayName":"B"},{"displayName":"C"}
                ]}
                """).size());
        assertEquals("ImZlo", source.parse("{\"schema\":1}").getFirst().displayName());
    }

    @Test
    void bundledFallbackSkinIsAStandardPlayerTexture() throws Exception {
        try (var stream = Objects.requireNonNull(
                SpecialThanksSupportTest.class.getClassLoader().getResourceAsStream(
                        "assets/lumi/special-thanks/nayakochii.png"))) {
            BufferedImage image = ImageIO.read(stream);
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
        }
    }

    @Test
    void resolverAcceptsOnlyHostedHttpsAndKeepsBlockingJoinOffSkinFor() throws Exception {
        assertEquals(URI.create("https://example.com/skin.png"),
                MinecraftSpecialThanksSkinResolver.requireHttps(
                        "https://example.com/skin.png"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftSpecialThanksSkinResolver.requireHttps(
                        "http://example.com/skin.png"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftSpecialThanksSkinResolver.requireHttps(
                        "https://user@example.com/skin.png"));

        String resolver = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/specialthanks/"
                        + "MinecraftSpecialThanksSkinResolver.java"));
        assertTrue(resolver.contains("Util.backgroundExecutor()"));
        assertTrue(resolver.indexOf(".join()") > resolver.indexOf("loadProfileSkin("));
        assertTrue(resolver.contains("new ClientAsset.ResourceTexture(id, id)"));
        assertTrue(resolver.contains("getNow(DefaultPlayerSkin.getDefaultSkin())"));
    }
}
