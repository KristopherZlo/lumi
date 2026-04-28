package io.github.luma.ui.overlay;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

final class CompareOverlayRenderTypes {

    private static final RenderType NORMAL_FILL = createFill("lumi_compare_overlay_fill");
    private static final RenderType NORMAL_OUTLINE = createOutline("lumi_compare_overlay_outline", false);
    private static final RenderType XRAY_FILL = createFill("lumi_compare_overlay_xray_fill");
    private static final RenderType XRAY_OUTLINE = createOutline("lumi_compare_overlay_xray_outline", true);

    private CompareOverlayRenderTypes() {
    }

    static RenderType fill(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_FILL : NORMAL_FILL;
    }

    static RenderType outline(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_OUTLINE : NORMAL_OUTLINE;
    }

    private static RenderType createFill(String name) {
        RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation("pipeline/" + name)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        RenderPipeline pipeline = pipelineBuilder.build();
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup()
        );
    }

    private static RenderType createOutline(String name, boolean xrayEnabled) {
        RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation("pipeline/" + name)
                .withDepthWrite(false);
        if (xrayEnabled) {
            pipelineBuilder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        }
        RenderPipeline pipeline = pipelineBuilder.build();
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup()
        );
    }
}
