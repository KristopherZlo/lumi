package io.github.luma.ui.onboarding;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.DoubleSupplier;

public final class OnboardingHoldProgressComponent extends BaseUIComponent {

    private static final int TRACK_COLOR = 0xFF25282B;
    private static final int FILL_COLOR = 0xFF4ADE80;

    private final DoubleSupplier progress;

    public OnboardingHoldProgressComponent(DoubleSupplier progress) {
        this.progress = progress == null ? () -> 0.0D : progress;
        this.sizing(Sizing.fill(100), Sizing.fixed(1));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return 120;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return 1;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        graphics.fill(this.x, this.y, this.x + this.width, this.y + 1, TRACK_COLOR);
        int filled = (int) Math.round(this.width * this.clamp(this.progress.getAsDouble()));
        if (filled > 0) {
            graphics.fill(this.x, this.y, this.x + filled, this.y + 1, FILL_COLOR);
        }
    }

    private double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
