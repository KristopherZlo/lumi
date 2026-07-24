package io.github.lumi.client.ui;

import io.github.lumi.client.specialthanks.MinecraftSpecialThanksSkinResolver;
import io.github.lumi.client.specialthanks.SpecialThanksCatalogSource;
import io.github.lumi.client.specialthanks.SpecialThanksEntry;
import java.util.List;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Bundled credits with lazily resolved, non-blocking player skin previews. */
public final class LumiSpecialThanksScreen extends LumiModalScreen {
    private static final int CARDS_TOP = 58;
    private static final int CARD_GAP = 8;
    private static final int MIN_CARD_HEIGHT = 46;
    private static final int BOTTOM_PADDING = 12;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_SCALE = 0.97F;
    private static final float PIVOT_Y = -1.0625F;
    private static final long WALK_CYCLE_MILLIS = 950L;
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
    private PlayerModel wideModel;
    private PlayerModel slimModel;
    private PlayerCapeModel capeModel;

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
        var models = minecraft.getEntityModels();
        wideModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
        slimModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        capeModel = new PlayerCapeModel(
                models.bakeLayer(ModelLayers.PLAYER_CAPE));
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
        PlayerModel model = skin.model() == PlayerModelType.SLIM
                ? slimModel : wideModel;
        float uiScale = LumiUiScale.current().renderScale(
                minecraft.getWindow().getGuiScale());
        float scale = FIT_SCALE * height / MODEL_HEIGHT;
        float rotationY = 25.0F
                + (float) (Math.floorMod(now, ORBIT_CYCLE_MILLIS)
                        / (double) ORBIT_CYCLE_MILLIS * 360.0D);
        if (skin.cape() != null) {
            poseCape(capeModel, now);
            graphics.submitSkinRenderState(
                    capeModel,
                    skin.cape().texturePath(),
                    scale * uiScale,
                    -5.0F,
                    rotationY,
                    PIVOT_Y,
                    scaled(x, uiScale),
                    scaled(y, uiScale),
                    scaled(x + width, uiScale),
                    scaled(y + height, uiScale));
        }
        poseWalking(model, now);
        graphics.submitSkinRenderState(
                model,
                skin.body().texturePath(),
                scale * uiScale,
                -5.0F,
                rotationY,
                PIVOT_Y,
                scaled(x, uiScale),
                scaled(y, uiScale),
                scaled(x + width, uiScale),
                scaled(y + height, uiScale));
    }

    private static void poseWalking(PlayerModel model, long now) {
        model.resetPose();
        model.setAllVisible(true);
        float swing = walkPhase(now);
        float leg = (float) Math.sin(swing) * 0.55F;
        float arm = leg * 0.75F;
        model.rightLeg.xRot = leg;
        model.leftLeg.xRot = -leg;
        model.rightArm.xRot = -arm;
        model.leftArm.xRot = arm;
        model.rightArm.zRot = 0.04F;
        model.leftArm.zRot = -0.04F;
        model.head.xRot = 0.06F;
    }

    private static void poseCape(PlayerCapeModel model, long now) {
        model.resetPose();
        model.body.getChild("cape").xRot = capeRotation(now);
    }

    static float capeRotation(long now) {
        return 0.26F + (float) Math.sin(walkPhase(now)) * 0.10F;
    }

    private static float walkPhase(long now) {
        return (float) (Math.floorMod(now, WALK_CYCLE_MILLIS)
                / (double) WALK_CYCLE_MILLIS * Math.PI * 2.0D);
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
