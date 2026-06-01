package io.github.luma.client.update;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class UpdateChatMessageFactory {

    private static final URI GITHUB_ISSUES_URI = URI.create("https://github.com/KristopherZlo/lumi/issues");
    private static final String BULLET = "\u2022";
    private static final Pattern NUMBERED_LIST_ITEM = Pattern.compile("^\\d+[.)]\\s+(.+)$");

    public Component create(UpdateRelease release) {
        MutableComponent message = Component.empty()
                .append(this.aqua("New "))
                .append(this.aqua("Lumi").withStyle(style -> style.withBold(true)))
                .append(this.aqua(" " + release.version() + " is available!"))
                .append("\n")
                .append(this.gray(release.version() + " changelog:"));
        for (String line : this.changelogLines(release.summary())) {
            message.append("\n").append(this.gray(line));
        }
        message.append("\n")
                .append(this.red("IMPORTANT: Report bugs to "))
                .append(this.githubIssuesLink())
                .append(this.red(" (click)!"));
        this.downloadUri(release).ifPresent(uri -> message.append("\n").append(this.downloadLink(uri)));
        return message;
    }

    private List<String> changelogLines(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        boolean plainLinesAreListItems = false;
        for (String rawLine : Arrays.asList(summary.split("\\R"))) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                plainLinesAreListItems = false;
                continue;
            }
            lines.add(this.formatChangelogLine(line, plainLinesAreListItems));
            plainLinesAreListItems = line.endsWith(":") || plainLinesAreListItems;
        }
        return lines;
    }

    private String formatChangelogLine(String line, boolean plainLineIsListItem) {
        String normalized = line.trim();
        if (normalized.startsWith(BULLET)) {
            return BULLET + " " + normalized.substring(1).trim();
        }
        if (normalized.startsWith("- ") || normalized.startsWith("* ")) {
            return BULLET + " " + normalized.substring(2).trim();
        }
        Matcher numbered = NUMBERED_LIST_ITEM.matcher(normalized);
        if (numbered.matches()) {
            return BULLET + " " + numbered.group(1).trim();
        }
        if (plainLineIsListItem && !normalized.endsWith(":")) {
            return BULLET + " " + normalized;
        }
        return normalized;
    }

    private MutableComponent aqua(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    private MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private MutableComponent red(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    private MutableComponent githubIssuesLink() {
        return this.red("[Github]").withStyle(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(GITHUB_ISSUES_URI))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open Lumi issues on GitHub"))));
    }

    private MutableComponent downloadLink(URI uri) {
        return Component.literal("[Download] (click)").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(uri))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("luma.update.chat_download_hover"))));
    }

    private Optional<URI> downloadUri(UpdateRelease release) {
        if (release == null || release.downloadUrl().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(release.downloadUrl()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
