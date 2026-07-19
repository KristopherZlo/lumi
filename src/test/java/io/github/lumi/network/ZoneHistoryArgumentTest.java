package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneHistoryArgumentTest {
    @Test
    void roundTripsZoneSaveAndRestore() {
        UUID zone = new UUID(0, 7);
        CommitId target = new CommitId(new ObjectId("a".repeat(64)));
        ZoneSaveArgument save = new ZoneSaveArgument(
                zone, "Clock works", VersionTags.parse("redstone, copper"));
        ZoneRestoreArgument restore = new ZoneRestoreArgument(zone, target);

        assertEquals(save, ZoneSaveArgument.parse(save.encode()));
        assertEquals(restore, ZoneRestoreArgument.parse(restore.encode()));
    }

    @Test
    void rejectsMissingMessageAndMalformedTarget() {
        UUID zone = new UUID(0, 7);
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneSaveArgument(zone, " "));
        assertThrows(IllegalArgumentException.class,
                () -> ZoneRestoreArgument.parse(zone + "\nnot-a-commit"));
    }
}
