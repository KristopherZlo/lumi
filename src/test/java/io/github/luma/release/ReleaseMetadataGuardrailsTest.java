package io.github.luma.release;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReleaseMetadataGuardrailsTest {

    @Test
    void fallbackUpdateManifestMatchesPackagedVersionAndDoesNotAdvertiseTestRelease() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"))) {
            properties.load(reader);
        }

        JsonObject manifest = JsonParser.parseString(Files.readString(Path.of("updates/lumi-fabric.json")))
                .getAsJsonObject();
        JsonObject release = manifest.getAsJsonArray("versions").get(0).getAsJsonObject();

        assertEquals(properties.getProperty("mod_version"), release.get("version").getAsString(),
                "Fallback update metadata must describe the packaged release version");
        assertFalse(release.get("title").getAsString().toLowerCase().contains("test"),
                "Public fallback update metadata must not advertise updater test releases");
        assertFalse(release.get("downloadUrl").getAsString().contains("updater-test"),
                "Public fallback update download URL must point at the release tag");
        assertFalse(release.get("changelogUrl").getAsString().contains("updater-test"),
                "Public fallback update changelog URL must point at the release tag");
    }

    @Test
    void modrinthPageDoesNotShipPlaceholderImageCopy() throws IOException {
        String page = Files.readString(Path.of("modrinth.md"));

        assertFalse(page.contains("AI placeholder"),
                "Modrinth copy must not describe release images as placeholders");
    }
}
