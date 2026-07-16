package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SaveScreenControllerTest {
    @Test
    void validatesNameAndSendsExactlyOneTrimmedSaveIntent() {
        var messages = new ArrayList<String>();
        var amendments = new ArrayList<String>();
        SaveScreenController controller = new SaveScreenController(
                messages::add, amendments::add);

        assertEquals("luma.status.save_name_required",
                controller.submit("  ", SaveScreenController.Intent.SAVE).error());
        SaveScreenController.Submission accepted = controller.submit(
                "  Clock works  ", SaveScreenController.Intent.SAVE);
        controller.submit("  Clock improved  ", SaveScreenController.Intent.AMEND);

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of("Clock works"), messages);
        assertEquals(java.util.List.of("Clock improved"), amendments);
    }

    @Test
    void keepsScreenOpenWhenNetworkingRejectsTheIntent() {
        SaveScreenController controller = new SaveScreenController(
                message -> {
                    throw new IllegalStateException("History is not synchronized");
                },
                message -> { });

        SaveScreenController.Submission result = controller.submit(
                "Idea", SaveScreenController.Intent.SAVE);

        assertEquals(false, result.accepted());
        assertEquals("History is not synchronized", result.error());
    }
}
