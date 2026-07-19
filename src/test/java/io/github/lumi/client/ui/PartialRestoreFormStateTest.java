package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.PartialRestorePlanPayload;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartialRestoreFormStateTest {
    @Test
    void selectionPreviewMustBeRepeatedAfterAnyManualEdit() {
        CommitId target = id('b');
        var form = new PartialRestoreFormState(target, Optional.empty());
        form.useSelection(new BlockBox(8, 70, 8, 2, 64, 4));

        assertEquals("2", form.minX());
        assertEquals("64", form.minY());
        assertEquals("4", form.minZ());
        assertTrue(form.selectionSource());

        BlockAreaTarget area = form.area().orElseThrow();
        UUID requestId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        form.beginPreview(requestId, area);
        assertTrue(form.accept(new PartialRestorePlanPayload(
                requestId, token, "minecraft:overworld", target,
                area, 3, 17, "")));

        assertTrue(form.canApply());
        assertEquals(Optional.of(token), form.previewToken());
        assertEquals(3, form.changedSections());
        assertEquals(17, form.changedBlocks());

        form.setMaxX("9");

        assertFalse(form.selectionSource());
        assertFalse(form.canApply());
        assertTrue(form.previewToken().isEmpty());
    }

    @Test
    void staleOrFailedPlanNeverEnablesApply() {
        CommitId target = id('c');
        var form = new PartialRestoreFormState(
                target, Optional.of(new BlockBox(0, 60, 0, 2, 62, 2)));
        BlockAreaTarget area = form.area().orElseThrow();
        UUID current = UUID.randomUUID();
        form.beginPreview(current, area);

        assertFalse(form.accept(new PartialRestorePlanPayload(
                UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld",
                target, area, 1, 1, "")));
        assertFalse(form.canApply());
        assertTrue(form.accept(new PartialRestorePlanPayload(
                current, new UUID(0, 0), "minecraft:overworld",
                target, area, 0, 0, "Save current work first")));
        assertFalse(form.canApply());
        assertEquals("Save current work first", form.error());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
