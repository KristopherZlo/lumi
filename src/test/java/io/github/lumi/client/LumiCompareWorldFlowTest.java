package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiCompareWorldFlowTest {
    @Test
    void everyCompareRouteClosesTheMenuAndUsesActionBarFeedback() throws Exception {
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertFalse(client.contains("LumiCompareScreen"));
        assertTrue(client.contains("client.setScreen(null)"));
        assertTrue(client.contains("NETWORKING.compare(target.before(), target.after())"));
        assertTrue(client.contains("NETWORKING.compareZone("));
        assertTrue(client.contains("\"luma.status.compare_loading\""));
        assertTrue(client.contains("\"luma.status.compare_no_changes\""));
    }
}
