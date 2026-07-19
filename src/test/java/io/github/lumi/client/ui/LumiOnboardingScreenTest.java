package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiOnboardingScreenTest {
    @Test
    void keepsTheLiveDashboardFreshBehindSpotlights() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOnboardingScreen.java"));

        assertTrue(source.contains(
                "background instanceof LumiDashboardScreen dashboard"));
        assertTrue(source.contains("dashboard.tick()"));
        assertTrue(source.contains("actions.save().open(this"));
        assertTrue(source.contains("actions.worldStep().accept(tour)"));
    }
}
