package io.github.luma.client.specialthanks;

import io.github.luma.mixin.client.PlayerCapeModelAccessor;
import io.github.luma.ui.LumaUiScale;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

public final class SpecialThanksPlayerShowcaseComponent extends BaseUIComponent {

    private static final int WIDTH = 76;
    private static final int HEIGHT = 112;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_SCALE = 0.97F;
    private static final float PIVOT_Y = -1.0625F;
    private static final float ROTATION_X = -5.0F;
    private static final float CAPE_ROTATION_X = (float) Math.toRadians(-10.0D);
    private static final long WALK_CYCLE_MILLIS = 950L;
    private static final long ORBIT_CYCLE_MILLIS = 9000L;

    private final SpecialThanksClientCache specialThanks = SpecialThanksClientCache.getInstance();
    private final SpecialThanksEntry entry;
    private final PlayerModel wideModel;
    private final PlayerModel slimModel;
    private final PlayerCapeModel capeModel;

    public SpecialThanksPlayerShowcaseComponent(String skinName) {
        this(new SpecialThanksEntry(skinName, skinName, ""));
    }

    public SpecialThanksPlayerShowcaseComponent(SpecialThanksEntry entry) {
        this.entry = entry == null ? new SpecialThanksEntry("", "", "") : entry;
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        this.wideModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
        this.slimModel = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.capeModel = new PlayerCapeModel(models.bakeLayer(ModelLayers.PLAYER_CAPE));
        this.sizing(Sizing.fixed(WIDTH), Sizing.fixed(HEIGHT));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return WIDTH;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return HEIGHT;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        Minecraft client = Minecraft.getInstance();
        PlayerSkin skin = this.specialThanks.skinFor(client, this.entry);
        PlayerModel model = skin.model() == PlayerModelType.SLIM ? this.slimModel : this.wideModel;
        long now = System.currentTimeMillis();

        this.poseWalking(model, now);
        float scale = FIT_SCALE * this.height / MODEL_HEIGHT;
        float lumaScale = LumaUiScale.renderScale(client.getWindow().getGuiScale());
        float rotationY = this.rotationY(now);
        this.attachCape(model, skin);
        graphics.submitSkinRenderState(
                model,
                skin.body().texturePath(),
                scale * lumaScale,
                ROTATION_X,
                rotationY,
                PIVOT_Y,
                scaled(this.x, lumaScale),
                scaled(this.y, lumaScale),
                scaled(this.x + this.width, lumaScale),
                scaled(this.y + this.height, lumaScale)
        );
    }

    private void attachCape(PlayerModel model, PlayerSkin skin) {
        ClientAsset.Texture cape = skin.cape();
        if (cape == null) {
            SpecialThanksCapeRenderRegistry.getInstance().clear(model);
            return;
        }
        this.capeModel.resetPose();
        ((PlayerCapeModelAccessor) (Object) this.capeModel).luma$cape().xRot = CAPE_ROTATION_X;
        SpecialThanksCapeRenderRegistry.getInstance().attach(model, this.capeModel, cape.texturePath());
    }

    private static int scaled(int coordinate, float scale) {
        return Math.round(coordinate * scale);
    }

    private void poseWalking(PlayerModel model, long now) {
        model.resetPose();
        model.setAllVisible(true);

        float swing = (float) ((now % WALK_CYCLE_MILLIS) / (double) WALK_CYCLE_MILLIS * Math.PI * 2.0D);
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

    private float rotationY(long now) {
        return 25.0F + (float) ((now % ORBIT_CYCLE_MILLIS) / (double) ORBIT_CYCLE_MILLIS * 360.0D);
    }
}
