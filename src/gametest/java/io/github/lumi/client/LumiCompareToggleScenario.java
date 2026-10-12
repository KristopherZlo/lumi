package io.github.lumi.client;

import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.CompareResultPayload;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
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
            context.waitFor(client -> owner.highlightProgress().preparing()
                    && owner.highlightProgress().percent() > 0, 400);
            assertPresentation(context.takeScreenshot("lumi-compare-preparing"), true, false);
            context.waitFor(client -> renderer.cachedBytes() > 0
                    && owner.highlightProgress().preparing(), 400);
            assertPresentation(context.takeScreenshot("lumi-compare-uploading"), true, false);
            context.getInput().pressKey(LumiCompareToggleScenario::toggleBinding);
            context.waitFor(client -> store.visibleChanges().isEmpty());
            context.waitTicks(2);
            context.runOnClient(client -> {
                if (owner.highlightProgress().preparing()) {
                    throw new AssertionError("Hidden Compare still advertises preparation");
                }
            });
            assertPresentation(context.takeScreenshot("lumi-compare-preparing-hidden"), false, false);
            context.getInput().pressKey(LumiCompareToggleScenario::toggleBinding);
            context.waitFor(client -> !store.visibleChanges().isEmpty());
            context.waitFor(client -> renderer.isReady() && renderer.cachedBytes() > 0, 400);
            context.waitTicks(2);
            assertPresentation(context.takeScreenshot("lumi-compare-prepared"), false, true);
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
                    if (!renderer.isReady() || renderer.cachedBytes() != bytes
                            || owner.highlightProgress().preparing()) {
                        throw new AssertionError("H rebuilt geometry instead of showing the retained highlight");
                    }
                });
            }
        } finally {
            context.runOnClient(client -> store.clear());
            context.waitFor(client -> renderer.cachedBytes() == 0);
            context.runOnClient(client -> {
                if (owner.highlightProgress().preparing()) {
                    throw new AssertionError("Cleared Compare left an active progress indicator");
                }
            });
        }
    }

    private static void assertPresentation(Path screenshot, boolean barExpected, boolean highlightExpected) {
        try {
            var image = ImageIO.read(screenshot.toFile());
            int pixels = 0;
            int outlines = 0;
            for (int y = 0; y < image.getHeight() / 2; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y) & 0xffffff;
                    if (rgb == 0x70d6a5) pixels++;
                    if (rgb == 0x55ff55 || rgb == 0xff5555 || rgb == 0xffd455) outlines++;
                }
            }
            if (barExpected ? pixels < 5 : pixels != 0) {
                throw new AssertionError("Unexpected preparation bar: " + pixels + " pixels in " + screenshot);
            }
            if (highlightExpected ? outlines < 100 : outlines != 0) {
                throw new AssertionError("Unexpected initial highlight: " + outlines + " pixels in " + screenshot);
            }
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("Cannot inspect preparation screenshot", failed);
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
