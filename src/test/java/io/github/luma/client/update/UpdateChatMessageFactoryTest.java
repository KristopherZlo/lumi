package io.github.luma.client.update;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateChatMessageFactoryTest {

    @Test
    void downloadTextUsesOpenUrlClickEvent() {
        String downloadUrl = "https://example.com/lumi.jar";
        Component message = new UpdateChatMessageFactory().create(release(downloadUrl));

        ClickEvent.OpenUrl event = clickUrl(flatten(message), "[Download] (click)").orElseThrow();

        assertEquals(URI.create(downloadUrl), event.uri());
    }

    @Test
    void chatMessageIncludesReleaseTitleAndFormattedSummary() {
        Component message = new UpdateChatMessageFactory().create(release(
                "https://example.com/lumi",
                "Important update",
                "First change\nSecond change"
        ));

        String text = message.getString();

        assertTrue(text.contains("First change"));
        assertTrue(text.contains("Second change"));
        assertTrue(text.contains("0.1.0-alpha.2 changelog:"));
        assertTrue(text.contains("\n"));
    }

    @Test
    void chatMessageUsesStyledTemplateLinksAndBulletedListItems() {
        Component message = new UpdateChatMessageFactory().create(release(
                "https://example.com/lumi/releases/tag/v0.1.0-alpha.2",
                "Ignored title",
                "- Added update modal\n* Fixed skip state\n1. Open release page"
        ));

        String text = message.getString();

        assertTrue(text.contains("New Lumi 0.1.0-alpha.2 is available!"));
        assertTrue(text.contains("0.1.0-alpha.2 changelog:"));
        assertTrue(text.contains("\u2022 Added update modal"));
        assertTrue(text.contains("\u2022 Fixed skip state"));
        assertTrue(text.contains("\u2022 Open release page"));
        assertTrue(text.contains("IMPORTANT: Report bugs to [Github] (click)!"));
        assertTrue(text.contains("[Download] (click)"));

        List<Component> parts = flatten(message);
        assertEquals(
                TextColor.fromLegacyFormat(ChatFormatting.AQUA),
                findPart(parts, "New ").orElseThrow().getStyle().getColor()
        );
        Component lumi = findPart(parts, "Lumi").orElseThrow();
        assertTrue(lumi.getStyle().isBold());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), lumi.getStyle().getColor());
        assertEquals(
                TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                findPart(parts, "\u2022 Added update modal").orElseThrow().getStyle().getColor()
        );
        assertEquals(
                TextColor.fromLegacyFormat(ChatFormatting.RED),
                findPart(parts, "IMPORTANT: Report bugs to ").orElseThrow().getStyle().getColor()
        );

        ClickEvent.OpenUrl github = clickUrl(parts, "[Github]").orElseThrow();
        assertEquals(URI.create("https://github.com/KristopherZlo/lumi/issues"), github.uri());
        ClickEvent.OpenUrl download = clickUrl(parts, "[Download] (click)").orElseThrow();
        assertEquals(URI.create("https://example.com/lumi/releases/tag/v0.1.0-alpha.2"), download.uri());
    }

    @Test
    void chatMessageTreatsPlainLinesAfterChangelogHeadingAsBulletedListItems() {
        Component message = new UpdateChatMessageFactory().create(release(
                "https://example.com/lumi/releases/tag/v0.1.0-alpha.2",
                "Ignored title",
                "Update test changes:\nStartup check now runs without local override flags.\nWorld chat shows one clickable update notice per version."
        ));

        String text = message.getString();

        assertTrue(text.contains("Update test changes:"));
        assertTrue(text.contains("\u2022 Startup check now runs without local override flags."));
        assertTrue(text.contains("\u2022 World chat shows one clickable update notice per version."));
    }

    private static UpdateRelease release(String downloadUrl) {
        return release(downloadUrl, "Lumi 0.1.0-alpha.2", "Summary");
    }

    private static UpdateRelease release(String downloadUrl, String title, String summary) {
        return new UpdateRelease(
                "0.1.0-alpha.2",
                100002,
                List.of("1.21.11"),
                "fabric",
                "alpha",
                title,
                summary,
                downloadUrl,
                "https://example.com/changelog",
                ""
        );
    }

    private static List<Component> flatten(Component component) {
        List<Component> parts = new ArrayList<>();
        collect(component, parts);
        return parts;
    }

    private static void collect(Component component, List<Component> parts) {
        parts.add(component);
        for (Component sibling : component.getSiblings()) {
            collect(sibling, parts);
        }
    }

    private static Optional<Component> findPart(List<Component> parts, String text) {
        return parts.stream()
                .filter(part -> text.equals(part.getString()))
                .findFirst();
    }

    private static Optional<ClickEvent.OpenUrl> clickUrl(List<Component> parts, String text) {
        return findPart(parts, text)
                .map(part -> part.getStyle().getClickEvent())
                .filter(ClickEvent.OpenUrl.class::isInstance)
                .map(ClickEvent.OpenUrl.class::cast);
    }
}
