package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PreviewFluidVertexConsumerTest {
    @Test
    void restoresEverySectionOffsetAndPreservesVertexAttributesWhenRebound() {
        var calls = new HashMap<String, Object[]>();
        VertexConsumer target = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[] {VertexConsumer.class}, (proxy, method, arguments) -> {
                    calls.put(method.getName(), arguments);
                    return proxy;
                });
        var fluid = new PreviewFluidVertexConsumer();
        for (BlockPos local : new BlockPos[] {new BlockPos(0, 0, 0), new BlockPos(17, 33, 48), new BlockPos(-17, -1, 16)}) {
            fluid.bind(target, local).addVertex((local.getX() & 15) + 0.25F,
                    (local.getY() & 15) + 0.5F, (local.getZ() & 15) + 0.75F)
                    .setColor(0x12345678).setUv(0.2F, 0.4F).setUv1(3, 7).setUv2(11, 13)
                    .setNormal(0, 1, 0).setLineWidth(2);
            assertArrayEquals(new Object[] {local.getX() + 0.25F, local.getY() + 0.5F, local.getZ() + 0.75F},
                    calls.get("addVertex"));
            assertArrayEquals(new Object[] {0x12345678}, calls.get("setColor"));
            assertArrayEquals(new Object[] {0.2F, 0.4F}, calls.get("setUv"));
            assertArrayEquals(new Object[] {3, 7}, calls.get("setUv1"));
            assertArrayEquals(new Object[] {11, 13}, calls.get("setUv2"));
            assertArrayEquals(new Object[] {0F, 1F, 0F}, calls.get("setNormal"));
        }
    }
}
