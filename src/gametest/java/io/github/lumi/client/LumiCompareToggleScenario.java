package io.github.lumi.client;

import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.CompareResultPayload;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/** Exercises the registered client owner and actual remappable H shortcut. */
@SuppressWarnings("UnstableApiUsage")
final class LumiCompareToggleScenario {
    void run(ClientGameTestContext context, List<BlockChange> fixture) {
        ClientCompareStore store = field(LumiClient.class, null, "COMPARISONS", ClientCompareStore.class);
        LumiPendingChangeOverlay owner = field(
                LumiClient.class, null, "PENDING_OVERLAY", LumiPendingChangeOverlay.class);
        LumiCompareOverlayRenderer renderer = field(
                LumiPendingChangeOverlay.class, owner, "compareOverlay", LumiCompareOverlayRenderer.class);
        try {
            context.runOnClient(client -> publish(store, fixture));
            context.waitFor(client -> renderer.isReady() && renderer.cachedBytes() > 0, 400);
            long bytes = context.computeOnClient(client -> renderer.cachedBytes());
            for (int cycle = 0; cycle < 3; cycle++) {
                context.getInput().pressKey(LumiCompareToggleScenario::toggleBinding);
                context.waitFor(client -> store.visibleChanges().isEmpty());
                context.waitTicks(3);
                context.runOnClient(client -> {
                    if (renderer.cachedBytes() != bytes || !renderer.isReady()) {
                        throw new AssertionError("H discarded prepared Compare geometry");
                    }
                });
                context.getInput().pressKey(LumiCompareToggleScenario::toggleBinding);
                context.waitFor(client -> !store.visibleChanges().isEmpty());
                context.runOnClient(client -> {
                    if (!renderer.isReady() || renderer.cachedBytes() != bytes) {
                        throw new AssertionError("H rebuilt geometry instead of showing the retained highlight");
                    }
                });
            }
        } finally {
            context.runOnClient(client -> store.clear());
            context.waitFor(client -> renderer.cachedBytes() == 0);
        }
    }

    private static void publish(ClientCompareStore store, List<BlockChange> fixture) {
        UUID request = UUID.randomUUID();
        CommitId before = new CommitId(new ObjectId("1".repeat(ObjectId.HEX_LENGTH)));
        CommitId after = new CommitId(new ObjectId("2".repeat(ObjectId.HEX_LENGTH)));
        store.begin(request, "minecraft:overworld", before, after);
        int batch = 0;
        for (int offset = 0; offset < fixture.size(); offset += CompareResultPayload.MAX_BLOCK_CHANGES) {
            var blocks = fixture.subList(offset,
                    Math.min(fixture.size(), offset + CompareResultPayload.MAX_BLOCK_CHANGES));
            store.accept(new CompareResultPayload(request, "minecraft:overworld", before, after,
                    0, 0, List.of(), List.of(), "", batch++, false, blocks, 0));
        }
        store.accept(new CompareResultPayload(request, "minecraft:overworld", before, after,
                1, 0, List.of(), List.of(), "", batch, true, List.of(), fixture.size()));
    }

    private static KeyMapping toggleBinding(Options options) {
        return Arrays.stream(options.keyMappings)
                .filter(mapping -> mapping.getName().equals("key.lumi.toggle_compare_overlay"))
                .findFirst().orElseThrow();
    }

    private static <T> T field(Class<?> type, Object instance, String name, Class<T> expected) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return expected.cast(field.get(instance));
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Cannot inspect registered Compare owner", failed);
        }
    }
}
