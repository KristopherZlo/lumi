package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistorySearchControllerTest {
    private final HistorySearchController controller = new HistorySearchController();

    @Test
    void filtersEveryWhitespaceTokenWithoutReorderingHistory() {
        var bridge = version('a', "Stone Bridge", "Alex", CommitKind.MANUAL);
        var tower = version('b', "Copper Tower", "Steve", CommitKind.ZONE);
        var garden = version('c', "Garden", "Alex", CommitKind.MANUAL);
        List<HistorySnapshotPayload.Version> versions = List.of(bridge, tower, garden);

        assertEquals(versions, controller.filter(versions, " \t "));
        assertEquals(List.of(bridge), controller.filter(versions, "STN   alex"));
        assertEquals(List.of(tower), controller.filter(versions, "bBb zone"));
        assertEquals(List.of(), controller.filter(versions, "alex zone"));
    }

    private static HistorySnapshotPayload.Version version(
            char digit, String message, String author, CommitKind kind) {
        return new HistorySnapshotPayload.Version(
                new CommitId(new ObjectId(
                        String.valueOf(digit).repeat(ObjectId.HEX_LENGTH))),
                message, author, 1L, kind);
    }
}
