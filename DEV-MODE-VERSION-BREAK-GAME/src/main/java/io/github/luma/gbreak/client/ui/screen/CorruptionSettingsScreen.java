package io.github.luma.gbreak.client.ui.screen;

import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

@Environment(EnvType.CLIENT)
public final class CorruptionSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 304;
    private static final int SLIDER_WIDTH = 288;
    private static final int SLIDER_HEIGHT = 20;
    private static final int PREVIEW_LEFT_OFFSET = 332;
    private static final int PREVIEW_TOP_OFFSET = 48;
    private static final int PREVIEW_SIZE = 188;
    private static final int PREVIEW_GRID_SIZE = 39;
    private static final int PREVIEW_VIEW_DISTANCE_CHUNKS = 16;
    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final CorruptionMaskSampler maskSampler = new CorruptionMaskSampler();

    public CorruptionSettingsScreen() {
        super(Text.literal("Corruption Settings"));
    }

    @Override
    protected void init() {
        int panelLeft = (this.width - PANEL_WIDTH) / 2;
        int sliderX = panelLeft + 24;
        int y = this.panelTop() + 44;
        this.addDrawableChild(new PercentSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Noise density",
                0.1D,
                65.0D,
                this.settings::noiseDensityPercent,
                this.settings::setNoiseDensityPercent
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Apply batch",
                1,
                96,
                this.settings::applyBatchSize,
                this.settings::setApplyBatchSize
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Restore batch",
                16,
                512,
                this.settings::restoreBatchSize,
                this.settings::setRestoreBatchSize
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Render radius %",
                25,
                100,
                this.settings::renderRadiusPercent,
                this.settings::setRenderRadiusPercent
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Vertical radius",
                1,
                12,
                this.settings::verticalRadius,
                this.settings::setVerticalRadius
        ));
        y += 24;
        this.addDrawableChild(new DoubleSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Noise scale",
                0.04D,
                0.25D,
                this.settings::noiseScale,
                this.settings::setNoiseScale
        ));
        y += 24;
        this.addDrawableChild(new DoubleSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Noise detail",
                0.08D,
                0.55D,
                this.settings::detailNoiseScale,
                this.settings::setDetailNoiseScale
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Ash bursts",
                0,
                40,
                this.settings::particleBurstsPerTick,
                this.settings::setParticleBurstsPerTick
        ));
        y += 24;
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Sky displays",
                0,
                160,
                this.settings::maxSkyDisplays,
                this.settings::setMaxSkyDisplays
        ));
        y += 30;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                .dimensions(sliderX + 86, y, 116, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelLeft = (this.width - PANEL_WIDTH) / 2;
        int panelTop = this.panelTop();
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xCC101115);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelTop + 14, 0xF3F3F3);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("Changes apply while the world keeps running"),
                this.width / 2,
                panelTop + 27,
                0xA9B0BA
        );
        this.renderPreview(context, panelLeft + PREVIEW_LEFT_OFFSET, panelTop + PREVIEW_TOP_OFFSET);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int panelTop() {
        return Math.max(18, (this.height - PANEL_HEIGHT) / 2);
    }

    private void renderPreview(DrawContext context, int left, int top) {
        this.fillBordered(context, left, top, PREVIEW_SIZE, PREVIEW_SIZE, 0xFF343943, 0xEA090B0E);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Preview"), left, top - 12, 0xF3F3F3);
        this.renderMaskPreview(context, left + 1, top + 1, PREVIEW_SIZE - 2);
        this.renderSkyDisplayPreview(context, left + 7, top + 8);
        this.renderParticlePreview(context, left + 1, top + 1, PREVIEW_SIZE - 2);

        int legendTop = top + PREVIEW_SIZE + 9;
        this.fillLegendSwatch(context, left, legendTop + 1, true);
        context.drawTextWithShadow(this.textRenderer, Text.literal("missing texture mask"), left + 14, legendTop, 0xCDD3DA);
        this.fillLegendSwatch(context, left, legendTop + 15, false);
        context.drawTextWithShadow(this.textRenderer, Text.literal("normal sky displays"), left + 14, legendTop + 14, 0xCDD3DA);
        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal(String.format(Locale.ROOT, "density %.1f%%  radius %d%%", this.settings.noiseDensityPercent(), this.settings.renderRadiusPercent())),
                left,
                legendTop + 31,
                0xA8B0BB
        );
    }

    private void renderMaskPreview(DrawContext context, int left, int top, int size) {
        int horizontalRadius = this.maskSampler.effectiveHorizontalRadius(PREVIEW_VIEW_DISTANCE_CHUNKS, this.settings);
        int cellSize = Math.max(2, size / PREVIEW_GRID_SIZE);
        int gridPixels = cellSize * PREVIEW_GRID_SIZE;
        int gridLeft = left + (size - gridPixels) / 2;
        int gridTop = top + (size - gridPixels) / 2;
        BlockPos center = this.previewCenter();
        int sampleStep = Math.max(1, horizontalRadius * 2 / PREVIEW_GRID_SIZE);

        for (int row = 0; row < PREVIEW_GRID_SIZE; row++) {
            for (int column = 0; column < PREVIEW_GRID_SIZE; column++) {
                int xOffset = (column - PREVIEW_GRID_SIZE / 2) * sampleStep;
                int zOffset = (row - PREVIEW_GRID_SIZE / 2) * sampleStep;
                BlockPos samplePos = new BlockPos(center.getX() + xOffset, center.getY(), center.getZ() + zOffset);
                double noiseValue = this.maskSampler.noiseValue(samplePos, this.settings);
                int shade = 20 + (int) Math.round(this.clamp((noiseValue + 1.0D) * 18.0D, 0.0D, 34.0D));
                int x = gridLeft + column * cellSize;
                int y = gridTop + row * cellSize;
                context.fill(x, y, x + cellSize, y + cellSize, 0xFF000000 | shade << 16 | shade << 8 | shade);
                if (this.maskSampler.isWorldMaskPosition(samplePos, noiseValue, this.settings)) {
                    this.fillMissingTextureCell(context, x, y, cellSize);
                }
            }
        }

        int centerX = gridLeft + PREVIEW_GRID_SIZE / 2 * cellSize;
        int centerY = gridTop + PREVIEW_GRID_SIZE / 2 * cellSize;
        context.fill(centerX - 2, centerY, centerX + cellSize + 2, centerY + 1, 0xFFECEFF4);
        context.fill(centerX, centerY - 2, centerX + 1, centerY + cellSize + 2, 0xFFECEFF4);
    }

    private void renderParticlePreview(DrawContext context, int left, int top, int size) {
        int particleCount = Math.min(80, this.settings.particleBurstsPerTick() * 2);
        long time = this.previewTime();
        for (int index = 0; index < particleCount; index++) {
            int x = left + 8 + (int) Math.round(this.hashUnit(index * 43L + time / 3L) * (size - 16));
            int y = top + 18 + (int) Math.round(this.hashUnit(index * 71L - time / 5L) * (size - 30));
            int color = index % 3 == 0 ? 0xFF777F7E : 0xFFA3AAA8;
            context.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void renderSkyDisplayPreview(DrawContext context, int left, int top) {
        int displayCount = Math.min(14, (this.settings.maxSkyDisplays() + 9) / 10);
        long time = this.previewTime();
        for (int index = 0; index < displayCount; index++) {
            int x = left + index % 7 * 20 + (int) Math.round(this.hashUnit(index * 17L + time) * 4.0D) - 2;
            int y = top + index / 7 * 12 + (int) Math.round(this.hashUnit(index * 29L - time) * 4.0D) - 2;
            if (index % 3 == 0) {
                this.fillMissingTextureCell(context, x, y, 8);
            } else {
                int color = switch (index % 5) {
                    case 0 -> 0xFF2D2A2F;
                    case 1 -> 0xFF3E4651;
                    case 2 -> 0xFF483A69;
                    case 3 -> 0xFF5D4038;
                    default -> 0xFF365B57;
                };
                context.fill(x, y, x + 8, y + 8, color);
                context.fill(x, y, x + 8, y + 1, 0x66FFFFFF);
            }
        }
    }

    private BlockPos previewCenter() {
        if (this.client == null || this.client.player == null) {
            return BlockPos.ORIGIN;
        }
        return this.client.player.getBlockPos();
    }

    private long previewTime() {
        if (this.client == null || this.client.world == null) {
            return 0L;
        }
        return this.client.world.getTime();
    }

    private void fillMissingTextureCell(DrawContext context, int x, int y, int size) {
        context.fill(x, y, x + size, y + size, 0xFFE600E6);
        int half = Math.max(1, size / 2);
        context.fill(x, y, x + half, y + half, 0xFF101010);
        context.fill(x + half, y + half, x + size, y + size, 0xFF101010);
    }

    private void fillLegendSwatch(DrawContext context, int x, int y, boolean missing) {
        if (missing) {
            this.fillMissingTextureCell(context, x, y, 8);
            return;
        }
        context.fill(x, y, x + 8, y + 8, 0xFF3E4651);
        context.fill(x, y, x + 8, y + 1, 0x66FFFFFF);
    }

    private void fillBordered(DrawContext context, int x, int y, int width, int height, int border, int fill) {
        context.fill(x, y, x + width, y + height, border);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    private double hashUnit(long value) {
        long hash = value ^ 0x9E3779B97F4A7C15L;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (hash >>> 11) * 0x1.0p-53D;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private abstract static class SettingSlider extends SliderWidget {
        private final String label;

        SettingSlider(int x, int y, int width, String label, double value) {
            super(x, y, width, SLIDER_HEIGHT, Text.empty(), value);
            this.label = label;
        }

        final String label() {
            return this.label;
        }
    }

    private static final class IntSettingSlider extends SettingSlider {
        private final int min;
        private final int max;
        private final IntSupplier getter;
        private final IntConsumer setter;

        IntSettingSlider(int x, int y, int width, String label, int min, int max, IntSupplier getter, IntConsumer setter) {
            super(x, y, width, label, normalize(getter.getAsInt(), min, max));
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(this.label() + ": " + this.getter.getAsInt()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(this.value());
        }

        private int value() {
            return this.min + (int) Math.round(this.value * (this.max - this.min));
        }
    }

    private static final class PercentSettingSlider extends SettingSlider {
        private final double min;
        private final double max;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        PercentSettingSlider(
                int x,
                int y,
                int width,
                String label,
                double min,
                double max,
                DoubleSupplier getter,
                DoubleConsumer setter
        ) {
            super(x, y, width, label, normalize(getter.getAsDouble(), min, max));
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(this.label() + ": " + String.format(Locale.ROOT, "%.1f%%", this.getter.getAsDouble())));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(this.value());
        }

        private double value() {
            return this.min + this.value * (this.max - this.min);
        }
    }

    private static final class DoubleSettingSlider extends SettingSlider {
        private final double min;
        private final double max;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        DoubleSettingSlider(
                int x,
                int y,
                int width,
                String label,
                double min,
                double max,
                DoubleSupplier getter,
                DoubleConsumer setter
        ) {
            super(x, y, width, label, normalize(getter.getAsDouble(), min, max));
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(this.label() + ": " + String.format(Locale.ROOT, "%.3f", this.getter.getAsDouble())));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(this.value());
        }

        private double value() {
            return this.min + this.value * (this.max - this.min);
        }
    }

    private static double normalize(double value, double min, double max) {
        return (value - min) / (max - min);
    }
}
