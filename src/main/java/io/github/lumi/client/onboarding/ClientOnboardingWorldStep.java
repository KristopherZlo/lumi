package io.github.lumi.client.onboarding;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Owns the two no-screen, in-world steps of the legacy onboarding tour. */
public final class ClientOnboardingWorldStep {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "onboarding_world_prompt");
    private static final int REQUIRED_EDITS = 5;
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private final ClientHistoryStore history;
    private final Runnable refresh;
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private Consumer<OnboardingTour> reopen;
    private OnboardingTour tour;
    private Set<HistorySnapshotPayload.PendingBlock> baselinePending = Set.of();
    private boolean baselineReady;
    private int edits;
    private int refreshCooldown;
    private long lastHoldSampleMillis;

    public ClientOnboardingWorldStep(
            ClientHistoryStore history,
            Runnable refresh) {
        this.history = Objects.requireNonNull(history, "history");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE, HUD_ID, this::render);
    }

    public void start(
            OnboardingTour activeTour,
            Consumer<OnboardingTour> continuation) {
        Objects.requireNonNull(activeTour, "activeTour");
        if (!activeTour.current().worldStep()) {
            throw new IllegalStateException(
                    "Onboarding can enter the world only from a world step");
        }
        tour = activeTour;
        reopen = Objects.requireNonNull(continuation, "continuation");
        var initial = pendingBlocks();
        baselinePending = initial.map(Set::copyOf).orElseGet(Set::of);
        baselineReady = initial.isPresent();
        edits = 0;
        refreshCooldown = 0;
        resetHold();
        refresh.run();
    }

    public boolean tracking() {
        return tour != null;
    }

    private void tick(Minecraft client) {
        if (tour == null || client.screen != null
                || client.player == null || client.level == null) {
            return;
        }
        if (tour.current().kind() == OnboardingTour.Kind.WORLD_PREVIEW) {
            tickPreview(client);
            return;
        }
        if (tour.current().kind() != OnboardingTour.Kind.WORLD_EDIT) {
            clear();
            return;
        }
        if (refreshCooldown-- <= 0) {
            refreshCooldown = REFRESH_INTERVAL_TICKS;
            refresh.run();
        }
        var current = pendingBlocks();
        if (current.isEmpty()) {
            return;
        }
        if (!baselineReady) {
            baselinePending = Set.copyOf(current.orElseThrow());
            baselineReady = true;
            return;
        }
        edits = trackedEdits(baselinePending, current.orElseThrow());
        if (edits >= REQUIRED_EDITS && tour.advanceWorldEdit()) {
            finishStep();
        }
    }

    private void tickPreview(Minecraft client) {
        boolean held = LumiHotkeys.actionModifierDown(
                client.options.keyMappings);
        long now = Util.getMillis();
        long elapsed = held && lastHoldSampleMillis > 0L
                ? Math.min(100L, now - lastHoldSampleMillis) : 0L;
        lastHoldSampleMillis = held ? now : 0L;
        boolean overlayVisible = held && pendingBlocks()
                .map(blocks -> !blocks.isEmpty()).orElse(false);
        if (holdGate.update(
                previewHoldActive(held, overlayVisible), elapsed)
                && tour.advancePendingPreview()) {
            finishStep();
        }
    }

    private void render(
            GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        Minecraft client = Minecraft.getInstance();
        if (tour == null || client.screen != null || client.options.hideGui) {
            return;
        }
        OnboardingTour.Page page = tour.current();
        int panelWidth = Math.min(330, graphics.guiWidth() - 16);
        int panelHeight = page.kind() == OnboardingTour.Kind.WORLD_EDIT ? 72 : 86;
        int x = (graphics.guiWidth() - panelWidth) / 2;
        int y = Math.max(8, graphics.guiHeight() - panelHeight - 30);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xe6111419);
        graphics.fill(x, y, x + 3, y + panelHeight, 0xff70d6a5);
        graphics.drawString(client.font,
                Component.translatable("luma.onboarding.header",
                        tour.displayIndex(), OnboardingTour.pageCount()),
                x + 10, y + 8, 0xff8f9aa8, false);
        graphics.drawString(client.font,
                Component.translatable(page.titleKey()),
                x + 10, y + 23, 0xff70d6a5, false);
        int cursorY = y + 39;
        if (page.kind() == OnboardingTour.Kind.WORLD_PREVIEW) {
            String key = LumiHotkeys.bindingLabel(
                    client.options.keyMappings, "key.lumi.action_modifier");
            graphics.drawString(client.font,
                    Component.translatable(
                            "luma.onboarding.preview_changes_hold")
                            .append(" [" + key + "]"),
                    x + 10, cursorY, 0xfff0f3f6, false);
            cursorY += 14;
        }
        for (var line : client.font.split(
                Component.translatable(page.helpKey()), panelWidth - 20)) {
            graphics.drawString(
                    client.font, line, x + 10, cursorY, 0xffc0c7d1, false);
            cursorY += 11;
            if (cursorY > y + panelHeight - 12) break;
        }
        if (page.kind() == OnboardingTour.Kind.WORLD_EDIT) {
            graphics.drawString(client.font,
                    Component.translatable(
                            "luma.onboarding.world_edit_counter",
                            Math.min(REQUIRED_EDITS, edits), REQUIRED_EDITS),
                    x + 10, y + panelHeight - 15, 0xff8f9aa8, false);
        } else {
            drawProgress(graphics, x + 10, y + panelHeight - 9, panelWidth - 20);
        }
    }

    private void drawProgress(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 3, 0xff343a43);
        graphics.fill(x, y,
                x + (int) Math.round(width * holdGate.progress()), y + 3,
                0xff70d6a5);
    }

    private Optional<List<HistorySnapshotPayload.PendingBlock>> pendingBlocks() {
        return history.state().snapshot()
                .map(HistorySnapshotPayload::pendingBlocks);
    }

    static int trackedEdits(
            Set<HistorySnapshotPayload.PendingBlock> baseline,
            List<HistorySnapshotPayload.PendingBlock> current) {
        return (int) current.stream().distinct()
                .filter(block -> !baseline.contains(block)).count();
    }

    static boolean previewHoldActive(boolean held, boolean overlayVisible) {
        return held && overlayVisible;
    }

    private void finishStep() {
        OnboardingTour completedTour = tour;
        Consumer<OnboardingTour> continuation = reopen;
        clear();
        continuation.accept(completedTour);
    }

    private void clear() {
        tour = null;
        reopen = null;
        baselinePending = Set.of();
        baselineReady = false;
        edits = 0;
        refreshCooldown = 0;
        resetHold();
    }

    private void resetHold() {
        holdGate.reset();
        lastHoldSampleMillis = 0L;
    }
}
