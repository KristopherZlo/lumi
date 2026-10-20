package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockChange;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiCompareOverlayRendererTest {
    @Test
    void effectPreviewKeepsFixedGeometryAndReversesPulseDirection() {
        var points = new java.util.ArrayList<float[]>();
        var colors = new java.util.ArrayList<Integer>();
        var vertices = (com.mojang.blaze3d.vertex.VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {com.mojang.blaze3d.vertex.VertexConsumer.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("addVertex")) points.add(new float[] {
                            (float) arguments[1], (float) arguments[2], (float) arguments[3]});
                    if (method.getName().equals("setColor")) colors.add((int) arguments[0]);
                    return proxy;
                });
        var pose = new com.mojang.blaze3d.vertex.PoseStack().last();
        for (var effect : new LumiEffectPreview.Effect[] {
                LumiEffectPreview.Effect.UNDO, LumiEffectPreview.Effect.REDO, LumiEffectPreview.Effect.RESTORE}) {
            float early = 0;
            for (double t : new double[] {.2, .8}) {
                points.clear(); colors.clear();
                LumiEffectPreview.renderShape(pose, vertices, effect, net.minecraft.world.phys.Vec3.ZERO,
                        new net.minecraft.world.phys.Vec3(1,0,0), new net.minecraft.world.phys.Vec3(0,1,0), t);
                assertEquals(effect == LumiEffectPreview.Effect.RESTORE ? 4 : 256, points.size());
                assertEquals(points.size(), colors.size());
                assertTrue(colors.stream().allMatch(color -> (color >>> 24) > 0));
                for (var point : points) {
                    assertTrue(Float.isFinite(point[0]) && Float.isFinite(point[1]));
                    assertTrue(Math.abs(point[0]) <= 1.6 && Math.abs(point[1]) <= 1.6);
                    assertEquals(0, point[2]);
                }
                if (t == .2) early = points.getFirst()[0];
                else if (effect == LumiEffectPreview.Effect.UNDO) assertTrue(points.getFirst()[0] < early);
                else assertTrue(points.getFirst()[0] > early);
            }
        }
    }

    @Test
    void usesDirectionalColors() {
        assertEquals(0xff55ff55,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.ADDED));
        assertEquals(0xffff5555,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.REMOVED));
        assertEquals(0xffffd455,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.CHANGED));
    }

    @Test
    void finishesAllFillsBeforeDrawingOutlines() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiCompareOverlayRenderer.java"));
        int fills = source.indexOf("draw(context, visible, xray, false)");
        int outlines = source.indexOf("draw(context, visible, xray, true)");
        assertTrue(fills >= 0 && fills < outlines);
    }
}
