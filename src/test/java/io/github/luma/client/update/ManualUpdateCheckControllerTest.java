package io.github.luma.client.update;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualUpdateCheckControllerTest {

    @Test
    void availableCheckReturnsNoticeForModal() {
        ManualUpdateCheckController controller = new ManualUpdateCheckController(() ->
                CompletableFuture.completedFuture(UpdateCheckResult.available(release("0.1.0-alpha.2"))));

        ManualUpdateCheckController.Result result = controller.checkNow().join();

        assertEquals(ManualUpdateCheckController.Status.UPDATE_AVAILABLE, result.status());
        assertTrue(result.notice().isPresent());
        assertEquals("0.1.0-alpha.2", result.notice().orElseThrow().version());
    }

    @Test
    void upToDateCheckReturnsLatestCompatibleState() {
        ManualUpdateCheckController controller = new ManualUpdateCheckController(() ->
                CompletableFuture.completedFuture(UpdateCheckResult.noneAvailable()));

        ManualUpdateCheckController.Result result = controller.checkNow().join();

        assertEquals(ManualUpdateCheckController.Status.UP_TO_DATE, result.status());
        assertTrue(result.notice().isEmpty());
    }

    @Test
    void unavailableCheckReturnsFailureState() {
        ManualUpdateCheckController controller = new ManualUpdateCheckController(() ->
                CompletableFuture.completedFuture(UpdateCheckResult.unavailable("IOException")));

        ManualUpdateCheckController.Result result = controller.checkNow().join();

        assertEquals(ManualUpdateCheckController.Status.UNAVAILABLE, result.status());
        assertEquals("IOException", result.detail());
    }

    private static UpdateRelease release(String version) {
        return new UpdateRelease(
                version,
                100002,
                List.of("1.21.11"),
                "fabric",
                "alpha",
                "Lumi " + version,
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        );
    }
}
