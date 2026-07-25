package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiStarWarsCrawlTest {
    @Test
    void acceptsOnlyTheTwoSearchSpells() {
        assertTrue(LumiDashboardScreen.starWarsSearch("star wars"));
        assertTrue(LumiDashboardScreen.starWarsSearch("STARWARS"));
        assertFalse(LumiDashboardScreen.starWarsSearch("star wars "));
    }

    @Test
    void upsideDownSpellsBypassHistoryFilteringRegardlessOfCase() {
        assertTrue(LumiDashboardScreen.upsideDownSearch("dinnerbone"));
        assertTrue(LumiDashboardScreen.upsideDownSearch("GRUMM"));
        assertEquals("", LumiDashboardScreen.historySearch("grumm"));
        assertEquals("", LumiDashboardScreen.historySearch("starwars"));
        assertEquals("grumm ", LumiDashboardScreen.historySearch("grumm "));
    }

    @Test
    void crawlCompressesLineSpacingTowardTheHorizon() {
        float nearGap = 14.0F
                * LumiStarWarsCrawl.projectionScale(14.0F, 180);
        float farGap = 154.0F
                * LumiStarWarsCrawl.projectionScale(154.0F, 180)
                - 140.0F
                * LumiStarWarsCrawl.projectionScale(140.0F, 180);

        assertTrue(nearGap > farGap);
    }

    @Test
    void crawlKeepsEverySaveInChronologicalOrder() {
        var newest = version('b', "Second", 20);
        var oldest = version('a', "Initial", 10);

        assertEquals(
                List.of(oldest, newest),
                LumiStarWarsCrawl.chronological(List.of(newest, oldest)));
    }

    private static HistorySnapshotPayload.Version version(
            char id, String name, long timestamp) {
        return new HistorySnapshotPayload.Version(
                new CommitId(new ObjectId(String.valueOf(id).repeat(64))),
                name, "Builder", timestamp, CommitKind.MANUAL);
    }
}
