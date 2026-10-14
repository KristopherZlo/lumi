package io.github.lumi.buildconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MinecraftBuildMetadataTest {
    @Test
    void artifactDependenciesMatchTheSelectedGroupWithoutFutureVersionRanges() throws Exception {
        JsonObject catalog = JsonParser.parseString(Files.readString(
                Path.of("gradle/minecraft-targets.json"))).getAsJsonObject();
        String selected = System.getProperty("lumi.test.target", catalog.get("defaultTarget").getAsString());
        JsonObject target = catalog.getAsJsonArray("targets").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals(selected))
                .findFirst().orElseThrow();
        JsonObject dependencies = resource("fabric.mod.json").getAsJsonObject("depends");
        assertEquals(target.get("minecraftVersions"), dependencies.get("minecraft"));
        assertEquals(">=" + target.get("javaVersion").getAsInt(), dependencies.get("java").getAsString());
        assertEquals(">=" + target.get("loaderVersion").getAsString(),
                dependencies.get("fabricloader").getAsString());
        assertEquals(">=" + target.get("fabricApiVersion").getAsString(),
                dependencies.get("fabric-api").getAsString());
        JsonObject mixins = resource("lumi.mixins.json");
        assertEquals("JAVA_" + target.get("javaVersion").getAsInt(),
                mixins.get("compatibilityLevel").getAsString());
        assertTrue(mixins.get("required").getAsBoolean());
        assertEquals(1, mixins.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
    }

    private JsonObject resource(String name) throws Exception {
        try (var stream = MinecraftBuildMetadataTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(stream, name);
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(text.contains("${"), name + " contains unexpanded build properties");
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }
}
