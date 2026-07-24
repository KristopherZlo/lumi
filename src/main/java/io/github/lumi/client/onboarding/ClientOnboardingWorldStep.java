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

/** Owns bounded world observation for the two no-screen onboarding steps. */
public final class ClientOnboardingWorldStep {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "onboarding_world_prompt");
    private static final int INITIAL_EDITS = 3;
    private static final int EXPERIMENT_EDITS = 10;
    private static final long PREVIEW_HOLD_NANOS = 1_500_000_000L;
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private final ClientHistoryStore history;
    private final Runnable refresh;
    private Consumer<OnboardingController> reopen;
    private OnboardingController controller;
    private Set<HistorySnapshotPayload.PendingBlock> baselinePending = Set.of();
    private boolean baselineReady;
    private int edits;
    private int refreshCooldown;
    private long previewHoldStarted;
    private boolean previewObserved;

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
            OnboardingController activeController,
            Consumer<OnboardingController> continuation) {
        Objects.requireNonNull(activeController, "activeController");
        if (!activeController.current().worldStep()) {
            throw new IllegalStateException(
                    "Onboarding can enter the world only from a world step");
        }
        controller = activeController;
        reopen = Objects.requireNonNull(continuation, "continuation");
        var initial = pendingBlocks();
        baselinePending = initial.map(Set::copyOf).orElseGet(Set::of);
        baselineReady = initial.isPresent();
        edits = 0;
        refreshCooldown = 0;
        resetPreviewHold();
        refresh.run();
    }

    public boolean tracking() {
        return controller != null;
    }

    public boolean accept(OnboardingEvent event) {
        if (controller == null) return false;
        OnboardingController.Effect effect = controller.handle(event);
        if (effect == OnboardingController.Effect.REOPEN) finishStep();
        return true;
    }

    private void tick(Minecraft client) {
        if (controller == null || client.screen != null
                || client.player == null || client.level == null) {
            return;
        }
        OnboardingTour.Kind kind = controller.current().kind();
        if (kind == OnboardingTour.Kind.WORLD_PREVIEW) {
            tickPreview(client);
            return;
        }
        if (kind == OnboardingTour.Kind.WORLD_UNDO_REDO) return;
        if (kind != OnboardingTour.Kind.WORLD_EDIT
                && kind != OnboardingTour.Kind.WORLD_EXPERIMENT) {
            clear();
            return;
        }
        tickEdits(kind);
    }

    private void tickEdits(OnboardingTour.Kind kind) {
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
        if (edits >= requiredEdits(kind)) {
            OnboardingController.Effect effect = controller.handle(
                    new OnboardingEvent.WorldCompleted(kind));
            if (effect == OnboardingController.Effect.REOPEN) finishStep();
        }
    }

    private void tickPreview(Minecraft client) {
        if (refreshCooldown-- <= 0) {
            refreshCooldown = REFRESH_INTERVAL_TICKS;
            refresh.run();
        }
        boolean eligible = LumiHotkeys.actionModifierDown(
                client.options.keyMappings)
                && pendingBlocks().map(blocks -> !blocks.isEmpty()).orElse(false);
        if (!eligible) {
            resetPreviewHold();
            return;
        }
        if (previewHoldStarted == 0L) {
            previewHoldStarted = System.nanoTime();
        }
        if (previewObserved) {
            controller.handle(new OnboardingEvent.WorldCompleted(
                    OnboardingTour.Kind.WORLD_PREVIEW));
            resetPreviewHold();
        }
    }

    private void render(
            GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        Minecraft client = Minecraft.getInstance();
        if (controller == null || client.screen != null || client.options.hideGui) {
            return;
        }
        OnboardingTour.Page page = controller.current();
        int panelWidth = Math.min(330, graphics.guiWidth() - 16);
        int panelHeight = panelHeight(page.kind());
        int x = (graphics.guiWidth() - panelWidth) / 2;
        int y = Math.max(8, graphics.guiHeight() - panelHeight - 30);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xe6111419);
        graphics.fill(x, y, x + 3, y + panelHeight, 0xff70d6a5);
        graphics.drawString(client.font,
                Component.translatable("luma.onboarding.header",
                        controller.displayIndex(), OnboardingTour.pageCount()),
                x + 10, y + 8, 0xff8f9aa8, false);
        graphics.drawString(client.font,
                Component.translatable(page.titleKey()),
                x + 10, y + 23, 0xff70d6a5, false);
        int cursorY = y + 39;
        if (page.kind() == OnboardingTour.Kind.WORLD_PREVIEW
                || page.kind() == OnboardingTour.Kind.WORLD_UNDO_REDO) {
            String key = worldBinding(client, page.kind());
            graphics.drawString(client.font,
                    Component.translatable(
                            instructionKey(page.kind()))
                            .append(" [" + key + "]"),
                    x + 10, cursorY, 0xfff0f3f6, false);
            cursorY += 14;
        }
        for (var line : client.font.split(
                worldHelp(page), panelWidth - 20)) {
            graphics.drawString(
                    client.font, line, x + 10, cursorY, 0xffc0c7d1, false);
            cursorY += 11;
            if (cursorY > y + panelHeight - 12) break;
        }
        if (page.kind() == OnboardingTour.Kind.WORLD_EDIT
                || page.kind() == OnboardingTour.Kind.WORLD_EXPERIMENT) {
            int required = requiredEdits(page.kind());
            graphics.drawString(client.font,
                    Component.translatable(
                            "luma.onboarding.world_edit_counter",
                            Math.min(required, edits), required),
                    x + 10, y + panelHeight - 15, 0xff8f9aa8, false);
        } else if (page.kind() == OnboardingTour.Kind.WORLD_PREVIEW) {
            float progress = previewProgress();
            int barX = x + 10;
            int barY = y + panelHeight - 15;
            int barWidth = panelWidth - 20;
            graphics.fill(barX, barY, barX + barWidth, barY + 5, 0xff30343a);
            graphics.fill(
                    barX, barY,
                    barX + Math.round(barWidth * progress), barY + 5,
                    0xff70d6a5);
            if (progress >= 1.0F) previewObserved = true;
        }
    }

    private Component worldHelp(OnboardingTour.Page page) {
        if (page.kind() != OnboardingTour.Kind.WORLD_UNDO_REDO) {
            return Component.translatable(page.helpKey());
        }
        return Component.translatable(controller.undoRedoPhase()
                == OnboardingController.UndoRedoPhase.UNDO
                ? "luma.onboarding.undo_redo_undo_help"
                : "luma.onboarding.undo_redo_redo_help");
    }

    private String worldBinding(Minecraft client, OnboardingTour.Kind kind) {
        String action = LumiHotkeys.bindingLabel(
                client.options.keyMappings, "key.lumi.action_modifier");
        if (kind == OnboardingTour.Kind.WORLD_PREVIEW) return action;
        String operation = LumiHotkeys.bindingLabel(
                client.options.keyMappings,
                controller.undoRedoPhase() == OnboardingController.UndoRedoPhase.UNDO
                        ? "key.lumi.undo" : "key.lumi.redo");
        return action + "] + [" + operation;
    }

    private String instructionKey(OnboardingTour.Kind kind) {
        if (kind == OnboardingTour.Kind.WORLD_PREVIEW) {
            return "luma.onboarding.preview_changes_hold";
        }
        return controller.undoRedoPhase() == OnboardingController.UndoRedoPhase.UNDO
                ? "luma.onboarding.undo_redo_undo"
                : "luma.onboarding.undo_redo_redo";
    }

    private float previewProgress() {
        return previewHoldStarted == 0L ? 0.0F
                : holdProgress(System.nanoTime() - previewHoldStarted);
    }

    static float holdProgress(long heldNanos) {
        return Math.max(0.0F, Math.min(1.0F,
                (float) heldNanos / PREVIEW_HOLD_NANOS));
    }

    private static int requiredEdits(OnboardingTour.Kind kind) {
        return kind == OnboardingTour.Kind.WORLD_EXPERIMENT
                ? EXPERIMENT_EDITS : INITIAL_EDITS;
    }

    private static int panelHeight(OnboardingTour.Kind kind) {
        return switch (kind) {
            case WORLD_PREVIEW -> 92;
            case WORLD_UNDO_REDO -> 86;
            default -> 72;
        };
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

    private void finishStep() {
        OnboardingController completedController = controller;
        Consumer<OnboardingController> continuation = reopen;
        clear();
        continuation.accept(completedController);
    }

    private void clear() {
        controller = null;
        reopen = null;
        baselinePending = Set.of();
        baselineReady = false;
        edits = 0;
        refreshCooldown = 0;
        resetPreviewHold();
    }

    private void resetPreviewHold() {
        previewHoldStarted = 0L;
        previewObserved = false;
    }
}
