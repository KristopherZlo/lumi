package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.OnboardingTour;

/** Computes stable spotlight holes around the live Dashboard controls. */
final class OnboardingSpotlightLayout {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 124;
    private static final int EDGE_GAP = 8;

    Placement place(
            OnboardingTour.Kind kind, int screenWidth, int screenHeight) {
        LumiPageLayout dashboard =
                LumiPageLayout.fit(screenWidth, screenHeight);
        Rect target = switch (kind) {
            case SPOTLIGHT_COMPARE -> changesTarget(dashboard);
            case SPOTLIGHT_RESTORE -> restoreTarget(dashboard);
            default -> throw new IllegalArgumentException(
                    "Page does not have a Dashboard spotlight");
        };
        return place(target, screenWidth, screenHeight);
    }

    Placement place(Rect target, int screenWidth, int screenHeight) {
        Rect hole = target.expand(5, screenWidth, screenHeight);
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 16));
        int panelX = clamp(
                hole.centerX() - panelWidth / 2,
                EDGE_GAP, Math.max(EDGE_GAP, screenWidth - panelWidth - EDGE_GAP));
        int panelY = hole.y() - PANEL_HEIGHT - EDGE_GAP;
        if (panelY < EDGE_GAP) {
            panelY = hole.bottom() + EDGE_GAP;
        }
        panelY = clamp(
                panelY, EDGE_GAP,
                Math.max(EDGE_GAP, screenHeight - PANEL_HEIGHT - EDGE_GAP));
        return new Placement(
                hole, new Rect(panelX, panelY, panelWidth, PANEL_HEIGHT));
    }

    private static Rect changesTarget(LumiPageLayout layout) {
        int available = Math.max(0, layout.bodyWidth() - 28);
        int buttonWidth = Math.max(0, (available - 70) / 2);
        return new Rect(
                layout.bodyX() + 26 + buttonWidth * 2,
                layout.bodyY() + 56, 26, 20);
    }

    private static Rect restoreTarget(LumiPageLayout layout) {
        var geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), 0);
        return new Rect(
                LumiDashboardScreen.historyActionX(
                        layout.bodyX(), layout.bodyWidth(), 0),
                LumiDashboardScreen.historyActionY(
                        geometry.latestY(), layout.bodyWidth()), 26, 18);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record Placement(Rect hole, Rect prompt) {
    }

    record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        int centerX() {
            return x + width / 2;
        }

        Rect expand(int padding, int maxWidth, int maxHeight) {
            int left = clamp(x - padding, 0, maxWidth);
            int top = clamp(y - padding, 0, maxHeight);
            int right = clamp(right() + padding, 0, maxWidth);
            int bottom = clamp(bottom() + padding, 0, maxHeight);
            return new Rect(
                    left, top, Math.max(1, right - left),
                    Math.max(1, bottom - top));
        }
    }
}
