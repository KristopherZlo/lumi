package io.github.luma.gbreak.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.luma.gbreak.GBreakDevMod;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

final class CorruptionRestoreFadeRenderLayer {

    static final RenderLayer LAYER = RenderLayer.of(
            "gbreakdev_corruption_restore_fade",
            RenderSetup.builder(CorruptionRestoreFadeRenderLayer.pipeline())
                    .translucent()
                    .expectedBufferSize(4096)
                    .build()
    );

    private CorruptionRestoreFadeRenderLayer() {
    }

    private static RenderPipeline pipeline() {
        Identifier shader = Identifier.of(GBreakDevMod.MOD_ID, "core/corruption_restore_fade");
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(GBreakDevMod.MOD_ID, "pipeline/corruption_restore_fade"))
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withCull(false)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build());
    }
}
