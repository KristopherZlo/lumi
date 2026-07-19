package io.github.lumi.client.ui;

import io.github.lumi.client.specialthanks.MinecraftSpecialThanksSkinResolver;
import io.github.lumi.client.specialthanks.SpecialThanksCatalogSource;
import io.github.lumi.client.specialthanks.SpecialThanksEntry;
import java.util.List;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Bundled credits with lazily resolved, non-blocking player skin previews. */
public final class LumiSpecialThanksScreen extends LumiLegacyModalScreen {
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
    private PlayerModel wideModel;
    private PlayerModel slimModel;

    public LumiSpecialThanksScreen(Screen parent) {
        super(Component.translatable("luma.screen.special_thanks.title"));
        this.parent = parent;
        skins = new MinecraftSpecialThanksSkinResolver(
                net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {
        beginLegacyInit();
        panelWidth = Math.min(390, width - 24);
        panelHeight = Math.min(320, height - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - panelHeight) / 2);
        var models = minecraft.getEntityModels();
        wideModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
        slimModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.special_thanks.help"),
                panelX + 16, panelY + 42, LegacyLumiTheme.MUTED, false);
        int cardHeight = Math.max(46, (panelHeight - 74) / 2);
        int y = panelY + 58;
        long now = System.currentTimeMillis();
        for (SpecialThanksEntry entry : entries) {
            entry(graphics, y, cardHeight, entry, now);
            y += cardHeight + 8;
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
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
        renderLegacyPanel(graphics, x, y, width, height);
        int modelHeight = height - 8;
        int modelWidth = Math.min(76, Math.max(34, modelHeight * 2 / 3));
        int modelX = x + 8;
        renderPlayer(graphics, entry, modelX, y + 4, modelWidth, modelHeight, now);
        int textX = modelX + modelWidth + 12;
        graphics.drawString(font, entry.displayName(), textX, y + 12,
                LegacyLumiTheme.TEXT, false);
        int textY = y + 27;
        for (var line : font.split(
                Component.literal(entry.description()),
                Math.max(1, x + width - textX - 8))) {
            graphics.drawString(font, line, textX, textY,
                    LegacyLumiTheme.MUTED, false);
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
        poseWalking(model, now);
        float uiScale = LumiUiScale.current().renderScale(
                minecraft.getWindow().getGuiScale());
        float scale = FIT_SCALE * height / MODEL_HEIGHT;
        graphics.submitSkinRenderState(
                model,
                skin.body().texturePath(),
                scale * uiScale,
                -5.0F,
                25.0F + (float) (Math.floorMod(now, ORBIT_CYCLE_MILLIS)
                        / (double) ORBIT_CYCLE_MILLIS * 360.0D),
                PIVOT_Y,
                scaled(x, uiScale),
                scaled(y, uiScale),
                scaled(x + width, uiScale),
                scaled(y + height, uiScale));
    }

    private static void poseWalking(PlayerModel model, long now) {
        model.resetPose();
        model.setAllVisible(true);
        float swing = (float) (Math.floorMod(now, WALK_CYCLE_MILLIS)
                / (double) WALK_CYCLE_MILLIS * Math.PI * 2.0D);
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

    private static int scaled(int coordinate, float scale) {
        return Math.round(coordinate * scale);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
