package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSaveScreenTest {
    @Test
    void amendEntryPrefillsTheLatestMessageAndMakesAmendPrimary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiSaveScreen.java"));

        assertTrue(source.contains("message.setValue(draftMessage)"));
        assertTrue(source.contains("tags.setValue(draftTags)"));
        assertTrue(source.contains("preferredIntent == SaveScreenController.Intent.AMEND"));
        assertTrue(source.contains("requestSubmit(preferredIntent)"));
        assertTrue(source.contains("new LumiAmendConfirmationScreen("));
        assertFalse(source.contains("luma.action.refresh_preview"));
        assertTrue(source.contains("submission.requestId().orElseThrow()"));
        assertTrue(source.contains("accepted.accept(requestId)"));
        assertTrue(source.contains("luma.history.tags_input"));
        assertTrue(source.contains("tags.getValue()"));
        assertTrue(source.contains("luma.zones.save_button"));
        assertTrue(source.contains("ZONE(\"luma.zones.save_title\""));
        assertTrue(source.contains("pending != observedPending"));
        assertFalse(source.contains("pending != observedPending && rebuildWidgets()"));
        assertTrue(source.contains("Component.literal(\"H2G2\")"));
        assertTrue(source.contains("savedName.accept(message.getValue())"));
        assertTrue(source.contains("Supplier<OptionalLong> pendingBlocks"));
        assertTrue(source.contains("pendingChanges().orElse(-1L) != 42L"));

        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        assertTrue(client.contains("PendingStatisticsPayload::workspace"));
        assertTrue(client.contains(
                "value.pendingRevision() == snapshot.pendingRevision()"));
        assertTrue(client.contains("statistics.total()"));
    }
}
