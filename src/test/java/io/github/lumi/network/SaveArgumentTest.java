package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaveArgumentTest {
    @Test
    void roundTripsVersionedUnicodeMessageAndCanonicalTags() {
        SaveArgument argument = new SaveArgument(
                "Башня готова", VersionTags.parse("#Roof, CASTLE"));

        assertEquals(argument, SaveArgument.parse(argument.encode()));
        assertEquals(
                new SaveArgument("Legacy save", VersionTags.empty()),
                SaveArgument.parse("Legacy save"));
    }

    @Test
    void commandValidationRejectsMalformedVersionedSaveAndAmend() {
        for (HistoryCommandPayload.Kind kind : java.util.List.of(
                HistoryCommandPayload.Kind.SAVE,
                HistoryCommandPayload.Kind.AMEND)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HistoryCommandPayload(
                            UUID.randomUUID(), kind, "LST1:not-base64:",
                            id('a'), 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SaveArgument.parse(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveArgument("line\nbreak", VersionTags.empty()));
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
