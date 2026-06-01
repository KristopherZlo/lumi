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

    private static UpdateRelease release(String downloadUrl) {
        return new UpdateRelease(
                "0.1.0-alpha.2",
                100002,
                List.of("1.21.11"),
                "fabric",
                "alpha",
                "Lumi 0.1.0-alpha.2",
                "Summary",
                downloadUrl,
                "https://example.com/changelog",
                ""
        );
    }
}
