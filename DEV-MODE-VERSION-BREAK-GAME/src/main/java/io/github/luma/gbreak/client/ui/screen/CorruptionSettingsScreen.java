package io.github.luma.gbreak.client.ui.screen;

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

@Environment(EnvType.CLIENT)
public final class CorruptionSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 336;
    private static final int SLIDER_WIDTH = 288;
    private static final int SLIDER_HEIGHT = 20;
    private final CorruptionSettings settings = CorruptionSettings.getInstance();

    public CorruptionSettingsScreen() {
        super(Text.literal("Corruption Settings"));
    }

    @Override
    protected void init() {
        int panelLeft = (this.width - PANEL_WIDTH) / 2;
        int sliderX = panelLeft + 24;
        int y = Math.max(34, (this.height - 254) / 2 + 44);
        this.addDrawableChild(new IntSettingSlider(
                sliderX,
                y,
                SLIDER_WIDTH,
                "Missing blocks",
                8,
                256,
                this.settings::targetCorruptedBlocks,
                this.settings::setTargetCorruptedBlocks
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
                "Horizontal radius",
                4,
                32,
                this.settings::horizontalRadius,
                this.settings::setHorizontalRadius
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
        this.renderBackground(context, mouseX, mouseY, delta);
        int panelLeft = (this.width - PANEL_WIDTH) / 2;
        int panelTop = Math.max(18, (this.height - 254) / 2);
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 254, 0xCC101115);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelTop + 14, 0xF3F3F3);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("Changes apply while the world keeps running"),
                this.width / 2,
                panelTop + 27,
                0xA9B0BA
        );
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
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
