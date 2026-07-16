package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BranchNameControllerTest {
    @Test
    void validatesAndSendsOneTrimmedBranchName() {
        var names = new ArrayList<String>();
        BranchNameController controller = new BranchNameController(names::add);

        assertEquals("luma.status.variant_name_required", controller.submit("  ").error());
        BranchNameController.Submission accepted = controller.submit("  clock idea  ");

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of("clock idea"), names);
    }

    @Test
    void reportsRejectedCreateIntent() {
        BranchNameController controller = new BranchNameController(name -> {
            throw new IllegalStateException("Branch already exists");
        });

        BranchNameController.Submission result = controller.submit("idea");

        assertEquals(false, result.accepted());
        assertEquals("Branch already exists", result.error());
    }
}
