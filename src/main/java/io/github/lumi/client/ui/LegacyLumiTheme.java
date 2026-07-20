package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** Colors and simple surfaces retained from the legacy Lumi presentation. */
final class LegacyLumiTheme {
    static final int PAGE_HEADER_HEIGHT = 42;
    static final int TEXT = 0xfff4f1ea;
    static final int MUTED = 0xffa9a39a;
    static final int ACCENT = 0xffd9b86c;
    static final int DANGER = 0xffff8585;
    static final int BACKDROP = 0xd608090a;
    static final int WINDOW = 0xff141517;
    static final int WINDOW_BORDER = 0xff45413a;
    static final int SIDEBAR = 0xff111214;
    static final int TITLEBAR = 0xff1c1d20;
    static final int PANEL = 0xef1a1b1e;
    static final int PANEL_BORDER = 0xff343238;
    static final int INSET = 0xea101113;
    static final int INSET_BORDER = 0xff2b2a2f;
    static final int CHIP = 0xff242326;
    static final int CHIP_BORDER = 0xff3c3830;
    static final int STATUS = 0xff211f18;
    static final int STATUS_BORDER = 0xff5a4724;

    private LegacyLumiTheme() {
    }

    static void outlined(
            GuiGraphics graphics, int x, int y, int width, int height,
            int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }
}
