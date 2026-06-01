package io.github.luma.client.update;

import java.net.URI;
import java.util.List;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateChatMessageFactoryTest {

    @Test
    void downloadTextUsesOpenUrlClickEvent() {
        String downloadUrl = "https://example.com/lumi.jar";
        Component message = new UpdateChatMessageFactory().create(release(downloadUrl));

        ClickEvent.OpenUrl event = message.getSiblings().stream()
                .map(sibling -> sibling.getStyle().getClickEvent())
                .filter(ClickEvent.OpenUrl.class::isInstance)
                .map(ClickEvent.OpenUrl.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(URI.create(downloadUrl), event.uri());
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
