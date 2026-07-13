package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksPlayerShowcaseComponentTest {

    @Test
    void showcaseUsesVanillaFullBodyRendererWithMotion() throws IOException {
        Path sourceFile = Path.of("src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java");

        assertTrue(Files.exists(sourceFile));

        String source = Files.readString(sourceFile);
        assertTrue(source.contains("PlayerModel"));
        assertTrue(source.contains("ModelLayers.PLAYER"));
        assertTrue(source.contains("ModelLayers.PLAYER_SLIM"));
        assertTrue(source.contains("ModelLayers.PLAYER_CAPE"));
        assertTrue(source.contains("PlayerCapeModel"));
        assertTrue(source.contains("SpecialThanksCapeRenderRegistry"));
        assertTrue(source.contains("SpecialThanksClientCache.getInstance()"));
        assertTrue(source.contains(".skinFor("));
        assertTrue(source.contains("submitSkinRenderState"));
        assertTrue(source.contains("skin.cape()"));
        assertTrue(source.contains("PlayerModelType.SLIM"));
        assertTrue(source.contains("Math.sin"));
        assertTrue(source.contains("rotationY"));
        assertTrue(source.contains("ORBIT_CYCLE_MILLIS = 18000L"));
    }

    @Test
    void capeIsAttachedToSkinRendererInsteadOfSeparateGuiLayer() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java"
        ));
        String mixin = Files.readString(Path.of(
                "src/client/java/io/github/luma/mixin/client/GuiSkinRendererMixin.java"
        ));
        String registry = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksCapeRenderRegistry.java"
        ));
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertFalse(source.contains("drawCape("));
        assertTrue(source.contains("attachCape("));
        assertTrue(source.contains("SpecialThanksCapeRenderRegistry.getInstance().attach"));
        assertTrue(mixin.contains("renderAttachedCape"));
        assertTrue(mixin.contains("GuiSkinRenderer"));
        assertFalse(mixin.contains("@Shadow"));
        assertTrue(mixin.contains("PictureInPictureRendererAccessor"));
        assertTrue(mixins.contains("client.PictureInPictureRendererAccessor"));
        assertTrue(registry.contains("renderToBuffer"));
    }

    @Test
    void capeSwaysSmoothlyBetweenFifteenAndTwentyDegrees() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java"
        ));
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(source.contains("PlayerCapeModelAccessor"));
        assertTrue(source.contains(".luma$cape().xRot = capeRotationX(now)"));
        assertTrue(mixins.contains("client.PlayerCapeModelAccessor"));
        assertEquals((float) Math.toRadians(-15.0D), SpecialThanksPlayerShowcaseComponent.capeRotationX(0L), 0.0001F);
        assertEquals((float) Math.toRadians(-20.0D), SpecialThanksPlayerShowcaseComponent.capeRotationX(750L), 0.0001F);
        assertEquals((float) Math.toRadians(-15.0D), SpecialThanksPlayerShowcaseComponent.capeRotationX(1500L), 0.0001F);
    }

    @Test
    void secondLayerUsesParentPartTransformOnly() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java"
        ));

        assertFalse(source.contains("copyRotation("));
        assertFalse(source.contains("hat.xRot"));
        assertFalse(source.contains("rightSleeve.xRot"));
        assertFalse(source.contains("leftSleeve.xRot"));
        assertFalse(source.contains("rightPants.xRot"));
        assertFalse(source.contains("leftPants.xRot"));
    }

    @Test
    void skinRenderStateUsesLumaTargetScale() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java"
        ));

        assertTrue(source.contains("LumaUiScale.current().renderScale"));
        assertTrue(source.contains("float lumaScale ="));
        assertTrue(source.contains("scale * lumaScale"));
        assertTrue(source.contains("scaled(this.x, lumaScale)"));
        assertTrue(source.contains("scaled(this.x + this.width, lumaScale)"));
    }
}
