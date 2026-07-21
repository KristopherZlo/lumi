package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerAuthoritativeNotificationTest {
    @Test
    void networkScreensDoNotPublishOptimisticSuccess() throws Exception {
        for (String file : List.of(
                "LumiBranchesScreen.java", "LumiBranchScreen.java",
                "LumiDeleteBranchScreen.java", "LumiDeletedVersionsScreen.java",
                "LumiDeleteVersionScreen.java", "LumiDeleteZoneScreen.java",
                "LumiMergeScreen.java", "LumiSaveScreen.java",
                "LumiPackageScreen.java",
                "LumiZoneDetailsScreen.java", "LumiZoneRestoreScreen.java",
                "LumiZonesScreen.java")) {
            String source = Files.readString(Path.of(
                    "src/main/java/io/github/lumi/client/ui", file));
            assertFalse(source.contains("displayClientMessage("), file);
            assertFalse(source.contains("Export started"), file);
            assertFalse(source.contains("Inspecting package"), file);
        }
    }
}
