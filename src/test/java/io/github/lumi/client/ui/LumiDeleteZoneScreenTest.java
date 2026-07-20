package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDeleteZoneScreenTest {
    @Test
    void requiresTheExactZoneNameAndPreservesHistoryContract() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDeleteZoneScreen.java"));

        assertTrue(source.contains(
                "zone.name().equals(confirmation.getValue())"));
        assertTrue(source.contains(
                "delete.accept(zone.id(), zone.revision())"));
        assertTrue(source.contains("luma.zones.delete_help"));
        assertTrue(source.contains("LumiButton.Kind.DANGER"));
    }
}
