package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksCatalogSourceTest {

    @Test
    void catalogSourceReadsGithubRawWithBundledFallback() throws IOException {
        Path sourceFile = Path.of("src/client/java/io/github/luma/client/specialthanks/SpecialThanksCatalogSource.java");

        assertTrue(Files.exists(sourceFile));

        String source = Files.readString(sourceFile);
        assertTrue(source.contains("https://raw.githubusercontent.com/KristopherZlo/lumi/main/src/main/resources/assets/lumi/special-thanks.json"));
        assertTrue(source.contains("assets/lumi/special-thanks.json"));
        assertTrue(source.contains("HttpClient"));
        assertTrue(source.contains("User-Agent"));
        assertTrue(source.contains("withBundledSkinAssets"));
    }

    @Test
    void remoteCatalogKeepsBundledSkinFallback() {
        SpecialThanksEntry remote = new SpecialThanksEntry(
                "Tester",
                "",
                "https://example.com/tester.png",
                "Tester"
        );
        SpecialThanksEntry bundled = new SpecialThanksEntry(
                "Tester",
                "",
                "https://example.com/tester.png",
                "lumi:special-thanks/tester.png",
                "Tester"
        );

        List<SpecialThanksEntry> merged = new SpecialThanksCatalogSource()
                .withBundledSkinAssets(List.of(remote), List.of(bundled));

        assertEquals("lumi:special-thanks/tester.png", merged.getFirst().skinAsset());
    }
}
