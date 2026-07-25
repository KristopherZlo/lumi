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
    void onlyStarWarsBypassesHistoryFiltering() {
        assertEquals("grumm", LumiDashboardScreen.historySearch("grumm"));
        assertEquals("", LumiDashboardScreen.historySearch("starwars"));
        assertEquals("grumm ", LumiDashboardScreen.historySearch("grumm "));
    }

    @Test
    void crawlWarpsGlyphCornersAsOnePerspectivePlane() {
        var nearLeft = LumiPerspectiveTextLayer.project(
                20.0F, 170.0F, 100.0F, 180.0F, 180);
        var farLeft = LumiPerspectiveTextLayer.project(
                20.0F, 40.0F, 100.0F, 180.0F, 180);
        var farRight = LumiPerspectiveTextLayer.project(
                180.0F, 40.0F, 100.0F, 180.0F, 180);

        assertTrue(farLeft.x() > nearLeft.x());
        assertEquals(100.0F, (farLeft.x() + farRight.x()) / 2.0F);
        assertTrue(farLeft.y() < nearLeft.y());
    }

    @Test
    void starsTwinkleAtIndependentTimes() {
        float first = LumiStarWarsCrawl.starPulse(0L, 0.0F, 0.002F);
        float later = LumiStarWarsCrawl.starPulse(300L, 0.0F, 0.002F);

        assertTrue(first != later);
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
