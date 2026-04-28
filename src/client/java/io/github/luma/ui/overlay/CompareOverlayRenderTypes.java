package io.github.luma.ui.overlay;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

final class CompareOverlayRenderTypes {

    private static final RenderType NORMAL_FILL = createFill("lumi_compare_overlay_fill", false);
    private static final RenderType NORMAL_OUTLINE = RenderTypes.linesTranslucent();
    private static final RenderType XRAY_FILL = createFill("lumi_compare_overlay_xray_fill", true);
    private static final RenderType XRAY_OUTLINE = createXrayOutline();

    private CompareOverlayRenderTypes() {
    }

    static RenderType fill(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_FILL : NORMAL_FILL;
    }

    static RenderType outline(boolean xrayEnabled) {
        return xrayEnabled ? XRAY_OUTLINE : NORMAL_OUTLINE;
    }

    private static RenderType createFill(String name, boolean xrayEnabled) {
        RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation("pipeline/" + name)
                .withCull(false);
        if (xrayEnabled) {
            pipelineBuilder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        }
        RenderPipeline pipeline = pipelineBuilder.build();
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline)
                        .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .sortOnUpload()
                        .createRenderSetup()
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
