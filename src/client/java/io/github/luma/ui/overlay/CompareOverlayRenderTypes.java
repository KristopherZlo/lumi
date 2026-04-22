package io.github.luma.ui.overlay;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

final class CompareOverlayRenderTypes {

    private static final RenderType NORMAL_FILL = RenderTypes.debugQuads();
    private static final RenderType NORMAL_OUTLINE = RenderTypes.linesTranslucent();
    private static final RenderType XRAY_FILL = createXrayFill();
    private static final RenderType XRAY_OUTLINE = createXrayOutline();

    private CompareOverlayRenderTypes() {
    }

    static RenderType fill(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_FILL : NORMAL_FILL;
    }

    static RenderType outline(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_OUTLINE : NORMAL_OUTLINE;
    }

    private static RenderType createXrayFill() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation("pipeline/lumi_compare_overlay_xray_fill")
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .build();
        return RenderType.create(
                "lumi_compare_overlay_xray_fill",
                RenderSetup.builder(pipeline).createRenderSetup()
        );
    }

    private static RenderType createXrayOutline() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation("pipeline/lumi_compare_overlay_xray_outline")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .build();
        return RenderType.create(
                "lumi_compare_overlay_xray_outline",
                RenderSetup.builder(pipeline).createRenderSetup()
        );
    }
}
