package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EntityMutationMixinSafetyTest {

    @Test
    void entitySnapCapturesOneOuterTransitionInsteadOfNestedPostMoveState() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/mixin/EntityMutationMixin.java"
        ));

        assertEquals(2, occurrences(source, "WorldMutationContext.pushCaptureSuppression()"));
        assertTrue(source.indexOf("luma$wrapSnapTo") < source.indexOf("WorldMutationContext.pushCaptureSuppression()"));
        assertTrue(source.indexOf("luma$wrapAbsSnapTo")
                < source.lastIndexOf("WorldMutationContext.pushCaptureSuppression()"));
    }

    private static int occurrences(String source, String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}
