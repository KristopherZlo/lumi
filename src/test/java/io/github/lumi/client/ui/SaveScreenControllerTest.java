package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SaveScreenControllerTest {
    @Test
    void validatesNameAndSendsExactlyOneTrimmedSaveIntent() {
        var messages = new ArrayList<String>();
        SaveScreenController controller = new SaveScreenController(messages::add);

        assertEquals("luma.status.save_name_required", controller.submit("  ").error());
        SaveScreenController.Submission accepted = controller.submit("  Clock works  ");

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of("Clock works"), messages);
    }

    @Test
    void keepsScreenOpenWhenNetworkingRejectsTheIntent() {
        SaveScreenController controller = new SaveScreenController(message -> {
            throw new IllegalStateException("History is not synchronized");
        });

        SaveScreenController.Submission result = controller.submit("Idea");

        assertEquals(false, result.accepted());
        assertEquals("History is not synchronized", result.error());
    }
}
