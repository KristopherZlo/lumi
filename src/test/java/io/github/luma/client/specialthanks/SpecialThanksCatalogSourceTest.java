package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
    }
}
