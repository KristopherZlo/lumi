package io.github.luma.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneDedicatedServerSmokeTest {

    @Test
    void mainInitializerRegistersServerNetworking() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/LumaMod.java"));

        assertTrue(source.contains("new WorkZoneServerNetworking()"));
        assertTrue(source.contains("this.workZoneNetworking.register()"));
    }

    @Test
    void serverNetworkingRequiresAdminAndSupportsSave() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/network/WorkZoneServerNetworking.java"));

        assertTrue(source.contains("LumaAccessControl.getInstance().canUse(player)"));
        assertTrue(source.contains("\"save\".equals(action)"));
        assertTrue(source.contains("this.versionService.startSaveVersion"));
    }

    @Test
    void dedicatedClientOpensZonesInsteadOfSingleplayerHistory() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/controller/ClientWorkspaceOpenService.java"));

        assertTrue(source.contains("!client.hasSingleplayerServer()"));
        assertTrue(source.contains("new WorkZoneScreen(parent, \"\")"));
    }
}
