package io.github.lumi.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Shared translucent Compare render layers with optional depth bypass. */
final class LumiCompareRenderTypes {
    private static final RenderType FILL = fill("lumi_compare_fill", false);
    private static final RenderType XRAY_FILL = fill("lumi_compare_xray_fill", true);
    private static final RenderType OUTLINE = outline("lumi_compare_outline", false);
    private static final RenderType XRAY_OUTLINE =
            outline("lumi_compare_xray_outline", true);

    private LumiCompareRenderTypes() { }

    static RenderType fill(boolean xray) {
        return xray ? XRAY_FILL : FILL;
    }

    static RenderType outline(boolean xray) {
        return xray ? XRAY_OUTLINE : OUTLINE;
    }

    private static RenderType fill(String name, boolean xray) {
        RenderPipeline.Builder pipeline =
                RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation("pipeline/" + name)
                        .withCull(false);
        if (xray) {
            pipeline.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        }
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline.build())
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup());
    }

    private static RenderType outline(String name, boolean xray) {
        RenderPipeline.Builder pipeline =
                RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation("pipeline/" + name)
                        .withDepthWrite(false);
        if (xray) {
            pipeline.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        }
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline.build())
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup());
    }
}
