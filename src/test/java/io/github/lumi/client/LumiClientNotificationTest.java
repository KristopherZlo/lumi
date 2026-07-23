package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiClientNotificationTest {
    @Test
    void rendersEveryTerminalOutcomeWithStateColor() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        int start = source.indexOf("private static void acceptOperationEvent(");
        int end = source.indexOf("private static void openZones(", start);
        String notifications = source.substring(start, end);

        assertFalse(notifications.contains("message().startsWith(\"luma.\")"));
        assertTrue(notifications.contains("showFeedback(event.message(), eventColor("));
        assertTrue(notifications.contains("case SUCCEEDED -> ChatFormatting.GREEN"));
        assertTrue(notifications.contains("case FAILED -> ChatFormatting.RED"));
        assertTrue(notifications.contains("case CANCELLED -> ChatFormatting.YELLOW"));
        assertTrue(notifications.contains("case RETURNED -> ChatFormatting.GOLD"));
        assertTrue(notifications.contains("case DEGRADED -> ChatFormatting.DARK_RED"));
        assertTrue(source.contains(
                "\"luma.status.survival_disabled\".equals(value)"));
        assertTrue(source.contains("NOTIFICATIONS.add(styled,"));
        assertTrue(source.contains("displayClientMessage(styled, true)"));
        assertTrue(source.contains(
                "HISTORY, PENDING_STATISTICS, NOTIFICATIONS"));
        assertTrue(source.contains(
                "workspace.hudDisplayMode() != HudDisplayMode.GUI"));
    }
}
