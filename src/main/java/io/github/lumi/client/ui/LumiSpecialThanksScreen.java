package io.github.lumi.client.ui;

import io.github.lumi.client.specialthanks.MinecraftSpecialThanksSkinResolver;
import io.github.lumi.client.specialthanks.SpecialThanksCatalogSource;
import io.github.lumi.client.specialthanks.SpecialThanksEntry;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Bundled credits with lazily resolved, non-blocking player skin previews. */
public final class LumiSpecialThanksScreen extends LumiModalScreen {
    private static final int CARDS_TOP = 58;
    private static final int CARD_GAP = 8;
    private static final int MIN_CARD_HEIGHT = 46;
    private static final int BOTTOM_PADDING = 12;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_SCALE = 0.97F;
    private static final float WALK_ANIMATION_SCALE = 0.6662F;
    private static final float CAPE_BASE_DEGREES = 6.0F;
    private static final long WALK_CYCLE_MILLIS = 950L;
    private static final long CAPE_CYCLE_MILLIS = 1_700L;
    private static final long ORBIT_CYCLE_MILLIS = 18_000L;
    private final Screen parent;
    private final List<SpecialThanksEntry> entries =
            new SpecialThanksCatalogSource().loadBundled();
    private final MinecraftSpecialThanksSkinResolver skins;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;

    public LumiSpecialThanksScreen(Screen parent) {
        super(Component.translatable("luma.screen.special_thanks.title"));
        this.parent = parent;
        skins = new MinecraftSpecialThanksSkinResolver(
                net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {
        beginScreenInit();
        panelWidth = Math.min(390, width - 24);
        panelHeight = Math.min(320, height - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - panelHeight) / 2);
        scroll = Math.min(scroll, Math.max(0, entries.size() - visibleRows()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.special_thanks.help"),
                panelX + 16, panelY + 42, LumiTheme.MUTED, false);
        int rows = visibleRows();
        int cardHeight = cardHeight(panelHeight, rows);
        int y = panelY + CARDS_TOP;
        long now = System.currentTimeMillis();
        for (int index = 0; index < rows; index++) {
            entry(graphics, y, cardHeight, entries.get(scroll + index), now);
            y += cardHeight + CARD_GAP;
        }
        renderScrollbar(
                graphics, panelX + 12, panelY + CARDS_TOP, panelWidth - 19,
                Math.max(0, panelHeight - CARDS_TOP - BOTTOM_PADDING),
                entries.size(), rows, scroll, value -> scroll = value);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void entry(
            GuiGraphics graphics,
            int y,
            int height,
            SpecialThanksEntry entry,
            long now) {
        int x = panelX + 16;
        int width = panelWidth - 32;
        renderPanel(graphics, x, y, width, height);
        int modelHeight = height - 8;
        int modelWidth = Math.min(76, Math.max(34, modelHeight * 2 / 3));
        int modelX = x + 8;
        renderPlayer(graphics, entry, modelX, y + 4, modelWidth, modelHeight, now);
        int textX = modelX + modelWidth + 12;
        graphics.drawString(font, entry.displayName(), textX, y + 12,
                LumiTheme.TEXT, false);
        int textY = y + 27;
        for (var line : font.split(
                Component.literal(entry.description()),
                Math.max(1, x + width - textX - 8))) {
            graphics.drawString(font, line, textX, textY,
                    LumiTheme.MUTED, false);
            textY += 11;
            if (textY >= y + height - 8) {
                break;
            }
        }
    }

    private void renderPlayer(
            GuiGraphics graphics,
            SpecialThanksEntry entry,
            int x,
            int y,
            int width,
            int height,
            long now) {
        PlayerSkin skin = skins.skinFor(entry);
        float uiScale = LumiUiScale.current().renderScale(
                minecraft.getWindow().getGuiScale());
        float scale = FIT_SCALE * height / MODEL_HEIGHT;
        AvatarRenderState state = playerRenderState(skin, now);
        Quaternionf cameraRotation = new Quaternionf()
                .rotateX((float) Math.toRadians(-5.0F));
        Quaternionf modelRotation = new Quaternionf()
                .rotateZ((float) Math.PI)
                .mul(cameraRotation);
        graphics.submitEntityRenderState(
                state,
                scale * uiScale,
                new Vector3f(0.0F, MODEL_HEIGHT / 2.0F, 0.0F),
                modelRotation,
                cameraRotation,
                scaled(x, uiScale),
                scaled(y, uiScale),
                scaled(x + width, uiScale),
                scaled(y + height, uiScale));
    }

    private static AvatarRenderState playerRenderState(PlayerSkin skin, long now) {
        float orbit = 25.0F
                + cyclePhase(now, ORBIT_CYCLE_MILLIS) * (float) (180.0D / Math.PI);
        AvatarRenderState state = new AvatarRenderState();
        state.skin = skin;
        state.showCape = skin.cape() != null;
        state.lightCoords = LightTexture.FULL_BRIGHT;
        state.bodyRot = 180.0F + orbit;
        state.yRot = 0.0F;
        state.xRot = 3.5F;
        state.walkAnimationPos = walkPhase(now) / WALK_ANIMATION_SCALE;
        state.walkAnimationSpeed = 0.4F;
        state.capeFlap = capeFlapDegrees(now);
        return state;
    }

    static float capeFlapDegrees(long now) {
        return capeAngleDegrees(now) - CAPE_BASE_DEGREES;
    }

    static float capeAngleDegrees(long now) {
        return -(15.0F
                + (float) Math.sin(cyclePhase(now, CAPE_CYCLE_MILLIS)) * 6.0F);
    }

    private static float walkPhase(long now) {
        return cyclePhase(now, WALK_CYCLE_MILLIS);
    }

    private static float cyclePhase(long now, long cycleMillis) {
        return (float) (Math.floorMod(now, cycleMillis)
                / (double) cycleMillis * Math.PI * 2.0D);
    }

    private static int scaled(int coordinate, float scale) {
        return Math.round(coordinate * scale);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= panelY + CARDS_TOP && y < panelY + panelHeight) {
            int maximum = Math.max(0, entries.size() - visibleRows());
            int replacement = Math.max(0, Math.min(
                    maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) scroll = replacement;
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int visibleRows() {
        return visibleCardRows(panelHeight, entries.size());
    }

    static int visibleCardRows(int panelHeight, int entryCount) {
        if (entryCount == 0) return 0;
        int available = Math.max(0, panelHeight - CARDS_TOP - BOTTOM_PADDING);
        int capacity = Math.max(1,
                (available + CARD_GAP) / (MIN_CARD_HEIGHT + CARD_GAP));
        return Math.min(entryCount, capacity);
    }

    static int cardHeight(int panelHeight, int rows) {
        if (rows == 0) return 0;
        int available = Math.max(1, panelHeight - CARDS_TOP - BOTTOM_PADDING);
        return Math.max(1, (available - (rows - 1) * CARD_GAP) / rows);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
