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
    void specialThanksCatalogStoresDisplayNameSkinNameAndDescription() throws IOException {
        Path catalog = Path.of("src/main/resources/assets/lumi/special-thanks.json");

        assertTrue(Files.exists(catalog));

        JsonObject root = JsonParser.parseString(Files.readString(catalog)).getAsJsonObject();
        JsonArray people = root.getAsJsonArray("people");
        JsonObject first = people.get(0).getAsJsonObject();

        assertEquals(1, root.get("schema").getAsInt());
        assertEquals("ImZlo", first.get("displayName").getAsString());
        assertEquals("ImZlo", first.get("skinName").getAsString());
        assertFalse(first.get("description").getAsString().toLowerCase().contains("skins"));
    }
}
