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
    void everyAdapterRegistersRequiredGameTestDiagnostics() throws Exception {
        var targets = JsonParser.parseString(Files.readString(Path.of("gradle/minecraft-targets.json")))
                .getAsJsonObject().getAsJsonArray("targets");
        for (var value : targets) {
            var target = value.getAsJsonObject();
            Path source = target.get("javaVersion").getAsInt() == 21 ? Path.of("src/gametest/resources")
                    : Path.of("versions", target.get("id").getAsString(), "src/gametest/resources");
            var configuration = JsonParser.parseString(Files.readString(source.resolve("lumi-gametest.mixins.json")))
                    .getAsJsonObject();
            var names = configuration.getAsJsonArray("mixins").asList().stream()
                    .map(element -> element.getAsString()).toList();
            assertTrue(names.contains("GameTestServerFailFastMixin"), source.toString());
            assertTrue(names.contains("GameTestSequenceFailureMixin"), source.toString());
            assertTrue(names.contains("AxiomVerificationFaultMixin"), source.toString());
            assertTrue(names.contains("AxiomBlockReadFaultMixin"), source.toString());
            assertTrue(configuration.getAsJsonArray("client").asList().stream()
                    .anyMatch(name -> name.getAsString().equals("ClientScreenTraceMixin")), source.toString());
        }
    }

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
