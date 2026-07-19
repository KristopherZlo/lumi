package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiComparePickerScreenTest {
    @Test
    void keepsIndependentColumnsAndDispatchesTheEyeIntoTheWorld()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiComparePickerScreen.java"));

        assertTrue(source.contains("leftPage"));
        assertTrue(source.contains("rightPage"));
        assertTrue(source.contains("leftHistory"));
        assertTrue(source.contains("rightHistory"));
        assertTrue(source.contains("leftSelection"));
        assertTrue(source.contains("rightSelection"));
        assertTrue(source.contains("history(left).nextBranch(snapshot.branches())"));
        assertTrue(source.contains("\"textures/gui/icons/see-changes.png\""));
        assertTrue(source.contains("\"eye-open\""));
        assertTrue(source.contains("minecraft.setScreen(null)"));
        assertTrue(source.contains("snapshot.dimensionId(), version.id()"));
    }
}
