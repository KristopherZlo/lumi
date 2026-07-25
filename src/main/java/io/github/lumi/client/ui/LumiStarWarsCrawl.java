package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Bounded perspective crawl built from the player's loaded Save metadata. */
final class LumiStarWarsCrawl {
    private static final long INTRO_MILLIS = 3_200L;
    private static final float PIXELS_PER_MILLI = 0.025F;
    private static final float PERSPECTIVE = 0.9F;
    private static final float MIN_SCALE = 0.22F;
    private static final int LINE_STRIDE = 14;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault());
    private final List<Star> stars = stars();
    private List<HistorySnapshotPayload.Version> source = List.of();
    private List<FormattedCharSequence> lines = List.of();
    private String worldName = "";
    private String playerName = "";
    private int wrapWidth;
    private long startedAt = Long.MIN_VALUE;

    void start(long nowMillis) {
        startedAt = nowMillis;
    }

    void startIfNeeded(long nowMillis) {
        if (startedAt == Long.MIN_VALUE) start(nowMillis);
    }

    void update(
            Font font,
            String worldName,
            String playerName,
            List<HistorySnapshotPayload.Version> versions,
            int width) {
        List<HistorySnapshotPayload.Version> ordered = chronological(versions);
        int boundedWidth = Math.max(40, width - 54);
        if (source.equals(ordered)
                && this.worldName.equals(worldName)
                && this.playerName.equals(playerName)
                && wrapWidth == boundedWidth) {
            return;
        }
        source = ordered;
        this.worldName = worldName;
        this.playerName = playerName;
        wrapWidth = boundedWidth;
        List<FormattedCharSequence> replacement = new ArrayList<>();
        append(replacement, font, Component.translatable(
                "luma.easter.star_wars.opening", worldName, playerName));
        for (int index = 0; index < ordered.size(); index++) {
            HistorySnapshotPayload.Version version = ordered.get(index);
            append(replacement, font, Component.translatable(
                    "luma.easter.star_wars.save." + index % 4,
                    VersionText.name(version),
                    DATE.format(Instant.ofEpochMilli(version.timestampMillis())),
                    version.author()));
        }
        lines = List.copyOf(replacement);
    }

    void render(
            GuiGraphics graphics, Font font,
            int x, int y, int width, int height, long nowMillis) {
        graphics.enableScissor(x, y, x + width, y + height);
        try {
            graphics.fill(x, y, x + width, y + height, 0xff000000);
            renderStars(graphics, x, y, width, height, nowMillis);
            long elapsed = Math.max(0L, nowMillis - startedAt);
            if (elapsed < INTRO_MILLIS) {
                renderIntro(graphics, font, x, y, width, height);
                return;
            }
            renderCrawl(
                    graphics, font, x, y, width, height,
                    elapsed - INTRO_MILLIS);
        } finally {
            graphics.disableScissor();
        }
    }

    private void renderIntro(
            GuiGraphics graphics, Font font,
            int x, int y, int width, int height) {
        List<FormattedCharSequence> intro = font.split(
                Component.translatable("luma.easter.star_wars.intro"),
                Math.max(40, width - 50));
        int lineY = y + (height - intro.size() * 11) / 2;
        for (FormattedCharSequence line : intro) {
            graphics.drawCenteredString(
                    font, line, x + width / 2, lineY, 0xff55c7ff);
            lineY += 11;
        }
    }

    private void renderCrawl(
            GuiGraphics graphics, Font font,
            int x, int y, int width, int height, long elapsed) {
        float bottom = y + height + 18.0F;
        float firstY = bottom - elapsed * PIXELS_PER_MILLI;
        float maximumDistance = height * (1.0F / MIN_SCALE - 1.0F)
                / PERSPECTIVE;
        int first = Math.max(0, (int) Math.ceil(
                (bottom - firstY - maximumDistance) / LINE_STRIDE));
        int last = Math.min(lines.size(), (int) Math.floor(
                (bottom - firstY) / LINE_STRIDE) + 1);
        for (int index = first; index < last; index++) {
            float distance = bottom - (firstY + index * LINE_STRIDE);
            float scale = projectionScale(distance, height);
            float lineY = bottom - distance * scale;
            int brightness = 90 + Math.round(scale * 165.0F);
            int color = 0xff000000 | brightness << 16
                    | Math.round(brightness * 0.84F) << 8;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + width / 2.0F, lineY);
            graphics.pose().scale(scale, scale);
            graphics.drawCenteredString(font, lines.get(index), 0, 0, color);
            graphics.pose().popMatrix();
        }
    }

    static float projectionScale(float distance, int height) {
        float plane = Math.max(1, height);
        return plane / (plane + Math.max(0.0F, distance) * PERSPECTIVE);
    }

    private void renderStars(
            GuiGraphics graphics,
            int x, int y, int width, int height, long nowMillis) {
        for (Star star : stars) {
            int brightness = 120 + Math.round(
                    (float) Math.sin(nowMillis / 500.0F + star.phase) * 45.0F);
            int color = 0xff000000
                    | brightness << 16 | brightness << 8 | brightness;
            int starX = x + Math.round(star.x * Math.max(0, width - 1));
            int starY = y + Math.round(star.y * Math.max(0, height - 1));
            graphics.fill(
                    starX, starY,
                    starX + star.size, starY + star.size, color);
        }
    }

    private void append(
            List<FormattedCharSequence> destination,
            Font font,
            Component paragraph) {
        destination.addAll(font.split(paragraph, wrapWidth));
        destination.add(Component.literal(" ").getVisualOrderText());
    }

    static List<HistorySnapshotPayload.Version> chronological(
            List<HistorySnapshotPayload.Version> versions) {
        return versions.stream()
                .sorted(Comparator.comparingLong(
                        HistorySnapshotPayload.Version::timestampMillis))
                .toList();
    }

    private static List<Star> stars() {
        Random random = new Random(0x5354415257415253L);
        List<Star> result = new ArrayList<>(72);
        for (int index = 0; index < 72; index++) {
            result.add(new Star(
                    random.nextFloat(), random.nextFloat(),
                    random.nextFloat() * (float) Math.PI * 2.0F,
                    index % 13 == 0 ? 2 : 1));
        }
        return List.copyOf(result);
    }

    private record Star(float x, float y, float phase, int size) { }
}
