package io.github.luma.client.update;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateProjectNoticeTest {

    @Test
    void createsCardStateForDownloadableRelease() {
        Optional<UpdateProjectNotice> notice = UpdateProjectNotice.from(Optional.of(release("https://example.com/lumi.jar")));

        assertTrue(notice.isPresent());
        assertEquals("0.1.0-alpha.2", notice.get().version());
        assertEquals("1.21.11", notice.get().minecraftVersion());
        assertEquals("https://example.com/lumi.jar", notice.get().downloadUrl());
    }

    @Test
    void skipsCardStateWithoutDownloadUrl() {
        assertTrue(UpdateProjectNotice.from(Optional.of(release(""))).isEmpty());
    }

    @Test
    void preservesChangeLinesAndCountsVisibleCharacters() {
        Optional<UpdateProjectNotice> notice = UpdateProjectNotice.from(Optional.of(release(
                "https://example.com/lumi.jar",
                """
                        Added automatic update checks.
                        Added world chat download notice.

                        Moved update prompt into a modal.
                        """
        )));

        assertTrue(notice.isPresent());
        assertEquals(List.of(
                "Added automatic update checks.",
                "Added world chat download notice.",
                "Moved update prompt into a modal."
        ), notice.get().changeLines());
        assertEquals(99, notice.get().changeCharacterCount());
    }

    private static UpdateRelease release(String downloadUrl) {
        return release(downloadUrl, "Summary");
    }

    private static UpdateRelease release(String downloadUrl, String summary) {
        return new UpdateRelease(
                "0.1.0-alpha.2",
                100002,
                List.of("1.21.11"),
                "fabric",
                "alpha",
                "Lumi 0.1.0-alpha.2",
                summary,
                downloadUrl,
                "https://example.com/changelog",
                ""
        );
    }
}
