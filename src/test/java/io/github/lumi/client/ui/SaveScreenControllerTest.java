package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import io.github.lumi.domain.model.VersionTags;
import org.junit.jupiter.api.Test;

class SaveScreenControllerTest {
    @Test
    void validatesNameAndSendsExactlyOneTrimmedSaveIntent() {
        var messages = new ArrayList<String>();
        var amendments = new ArrayList<String>();
        var tags = new ArrayList<VersionTags>();
        UUID saveRequest = UUID.randomUUID();
        SaveScreenController controller = new SaveScreenController(
                (message, submittedTags) -> {
                    messages.add(message);
                    tags.add(submittedTags);
                    return saveRequest;
                },
                (message, submittedTags) -> {
                    amendments.add(message);
                    return UUID.randomUUID();
                });

        assertEquals("luma.status.save_name_required",
                controller.submit("  ", SaveScreenController.Intent.SAVE).error());
        SaveScreenController.Submission accepted = controller.submit(
                "  Clock works  ", " Redstone, #Tower ",
                SaveScreenController.Intent.SAVE);
        controller.submit("  Clock improved  ", SaveScreenController.Intent.AMEND);

        assertTrue(accepted.accepted());
        assertEquals(Optional.of(saveRequest), accepted.requestId());
        assertEquals(java.util.List.of("Clock works"), messages);
        assertEquals(java.util.List.of("Clock improved"), amendments);
        assertEquals(java.util.List.of(
                new VersionTags(java.util.List.of("redstone", "tower"))), tags);
    }

    @Test
    void keepsScreenOpenWhenNetworkingRejectsTheIntent() {
        SaveScreenController controller = new SaveScreenController(
                (message, tags) -> {
                    throw new IllegalStateException("History is not synchronized");
                },
                (message, tags) -> UUID.randomUUID());

        SaveScreenController.Submission result = controller.submit(
                "Idea", SaveScreenController.Intent.SAVE);

        assertEquals(false, result.accepted());
        assertEquals("History is not synchronized", result.error());
    }

    @Test
    void revalidatesScopeBeforeSendingSaveOrConfirmedAmend() {
        var calls = new ArrayList<String>();
        var reason = new java.util.concurrent.atomic.AtomicReference<>("luma.dashboard.pending_calculating");
        var controller = new SaveScreenController((message, tags) -> {
            calls.add("save"); return UUID.randomUUID();
        }, (message, tags) -> {
            calls.add("amend"); return UUID.randomUUID();
        }, ignored -> reason.get());
        assertFalse(controller.submit("Build", SaveScreenController.Intent.SAVE).accepted());
        reason.set("");
        assertTrue(controller.submit("Build", SaveScreenController.Intent.SAVE).accepted());
        reason.set("luma.zones.pending_clean");
        assertFalse(controller.submit("Build", SaveScreenController.Intent.AMEND).accepted());
        assertEquals(java.util.List.of("save"), calls);
    }
}
