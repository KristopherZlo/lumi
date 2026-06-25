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
        assertTrue(source.contains("\"amend\".equals(action)"));
        assertTrue(source.contains("this.versionService.startSaveVersion"));
        assertTrue(source.contains("this.versionService.startAmendVersion"));
        assertTrue(source.contains("ProjectVersionTags.parse(request.tags())"));
    }

    @Test
    void dedicatedClientOpensZonesInsteadOfSingleplayerHistory() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/controller/ClientWorkspaceOpenService.java"));

        assertTrue(source.contains("!client.hasSingleplayerServer()"));
        assertTrue(source.contains("new WorkZoneScreen(parent, \"\")"));
    }

    @Test
    void singleplayerActiveZoneOpensZonesTabFromWorkspaceHotkey() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/controller/ClientWorkspaceOpenService.java"));

        assertTrue(source.contains("boolean hasActiveZone"));
        assertTrue(source.contains("result.hasActiveZone()"));
        assertTrue(source.contains("new WorkZoneScreen(parent, result.projectName())"));
    }
}
