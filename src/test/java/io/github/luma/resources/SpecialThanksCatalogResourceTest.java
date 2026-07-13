package io.github.luma.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksCatalogResourceTest {

    @Test
    void specialThanksCatalogStoresDisplayNameSkinNameSkinUrlAndDescription() throws IOException {
        Path catalog = Path.of("src/main/resources/assets/lumi/special-thanks.json");

        assertTrue(Files.exists(catalog));

        JsonObject root = JsonParser.parseString(Files.readString(catalog)).getAsJsonObject();
        JsonArray people = root.getAsJsonArray("people");
        JsonObject first = people.get(0).getAsJsonObject();
        JsonObject tester = people.get(1).getAsJsonObject();

        assertEquals(2, root.get("schema").getAsInt());
        assertEquals("ImZlo", first.get("displayName").getAsString());
        assertEquals("ImZlo", first.get("skinName").getAsString());
        assertTrue(first.has("skinUrl"));
        assertTrue(first.has("skinAsset"));
        assertFalse(first.get("description").getAsString().toLowerCase().contains("skins"));
        assertEquals("Nayakochii", tester.get("displayName").getAsString());
        assertTrue(tester.get("skinUrl").getAsString().startsWith("https://raw.githubusercontent.com/"));
        assertEquals("lumi:special-thanks/nayakochii.png", tester.get("skinAsset").getAsString());
        assertTrue(Files.exists(Path.of("src/main/resources/assets/lumi/special-thanks/nayakochii.png")));
    }
}
