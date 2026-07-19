package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneOverlayNetworkingTest {
    @Test
    void correlatesRequestsAndReceivesBoundedShellBatches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClientNetworking.java"));

        assertTrue(source.contains(
                "ClientPlayNetworking.registerGlobalReceiver(\n"
                        + "                ZoneOverlayPayload.TYPE"));
        assertTrue(source.contains("zoneOverlays.begin("));
        assertTrue(source.contains(
                "HistoryCommandPayload.Kind.ZONE_OVERLAY"));
        assertTrue(source.contains("zoneOverlays.clear()"));
    }
}
