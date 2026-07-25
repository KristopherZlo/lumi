package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class LumiNbsSongTest {
    @Test
    void bundledSongIsAReadableBoundedNbsArrangement() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/assets/lumi/music/star_wars.nbs")) {
            assertNotNull(input);
            LumiNbsSong song = LumiNbsSong.read(input);

            assertEquals(7.25F, song.ticksPerSecond());
            assertEquals(581, song.notes().size());
            assertTrue(song.notes().stream().allMatch(
                    note -> note.instrument() >= 0
                            && note.instrument() < 16));
        }
    }

    @Test
    void outOfRangeNotesFoldIntoMinecraftsPlayablePitchRange() {
        assertTrue(LumiNbsPlayer.foldedPitch(0, 0) >= 0.5F);
        assertTrue(LumiNbsPlayer.foldedPitch(87, 0) <= 2.0F);
        assertEquals(1.0F, LumiNbsPlayer.foldedPitch(45, 0));
    }
}
