package io.github.lumi.client.ui;

import com.mojang.brigadier.arguments.BoolArgumentType;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;

/** Development-only HUD samples. No command changes world or history state. */
public final class LumiEffectPreview {
    enum State {
        IDLE("main", "All saved", 24, false, 0), DIRTY("main", "24 edits", 24, false, 0),
        SAVE("Saved", "Courtyard arch", 42, false, 2400),
        BRANCH("roof-study", "Switched from main", 42, false, 2400),
        UNDO("Edit undone", "Brush stroke", 42, false, 1800),
        REDO("Edit restored", "Brush stroke", 42, false, 1800),
        RESTORE("Restoring world", "Courtyard", 50, true, 4200),
        COMPARE("Preparing Compare", "main to roof-study", 50, true, 4200),
        ERROR("Restore blocked", "Newer edits overlap", 42, false, 0);
        final String title, detail;
        final int height, holdMillis;
        final boolean progress;
        State(String title, String detail, int height, boolean progress, int holdMillis) {
            this.title = title; this.detail = detail; this.height = height;
            this.progress = progress; this.holdMillis = holdMillis;
        }
    }
    private static final State[] DEMO = {State.IDLE, State.DIRTY, State.SAVE,
            State.BRANCH, State.UNDO, State.REDO, State.RESTORE, State.COMPARE, State.ERROR, State.IDLE};
    private final Motion motion = new Motion();
    private ClientLevel world;
    private int demoIndex = -1;
    private long demoStarted;

    public static void register(BooleanSupplier realFeedbackVisible) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        var preview = new LumiEffectPreview();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = ClientCommandManager.literal("lumi-effect").executes(context -> preview.start(State.SAVE));
            for (var state : State.values()) command.then(ClientCommandManager
                    .literal(state.name().toLowerCase(Locale.ROOT)).executes(context -> preview.start(state)));
            command.then(ClientCommandManager.literal("off").executes(context -> { preview.clear(); return 1; }));
            command.then(ClientCommandManager.literal("demo").executes(context -> {
                int result = preview.start(State.IDLE);
                if (result != 0) { preview.demoIndex = 0; preview.demoStarted = System.nanoTime(); }
                return result;
            }));
            command.then(ClientCommandManager.literal("motion").then(ClientCommandManager
                    .argument("enabled", BoolArgumentType.bool()).executes(context -> {
                        preview.motion.animated = BoolArgumentType.getBool(context, "enabled");
                        LumiMotion.setEnabled(preview.motion.animated);
                        return 1;
                    })));
            dispatcher.register(command);
        });
        HudElementRegistry.replaceElement(LumiOperationHud.ID, original -> (graphics, delta) -> {
            if (preview.world != null && realFeedbackVisible.getAsBoolean()) preview.clear();
            if (!preview.render(graphics)) {
                //? if >=26.1 {
                /*original.extractRenderState(graphics, delta);
                *///?} else {
                original.render(graphics, delta);
                //?}
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> preview.clear());
    }

    private int start(State state) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        world = client.level; demoIndex = -1;
        motion.change(state, System.nanoTime());
        return 1;
    }

    private void clear() { world = null; demoIndex = -1; motion.reset(); }

    private boolean render(GuiGraphics graphics) {
        var client = Minecraft.getInstance();
        if (world == null) return false;
        if (world != client.level) { clear(); return false; }
        if (client.screen instanceof LumiScreen) return false;
        long now = System.nanoTime();
        if (demoIndex >= 0) {
            int index = (int) ((now-demoStarted) / 3_000_000_000L);
            if (index >= DEMO.length) { demoIndex = -1; motion.change(State.IDLE, now); }
            else if (index != demoIndex) { demoIndex = index; motion.change(DEMO[index], now); }
        } else if (motion.state.holdMillis > 0 && (now-motion.updated)/1_000_000 >= motion.state.holdMillis) {
            motion.change(State.IDLE, now);
        }
        if (client.options.hideGui) return true;
        var frame = motion.frame(now);
        if (frame.opacity < .04) return true;
        int width = Math.min(224, Math.max(1, graphics.guiWidth()-20));
        int x = graphics.guiWidth()-width-10, y = 10, height = (int) Math.round(frame.height);
        graphics.fill(x+1, y+2, x+width+1, y+height+2, LumiTheme.withOpacity(0xff000000, (float) (frame.opacity*.18)));
        LumiTheme.outlined(graphics, x, y, width, height,
                LumiTheme.withOpacity(LumiTheme.WINDOW, (float) (frame.opacity*.9)),
                LumiTheme.withOpacity(LumiTheme.WINDOW_BORDER, (float) (frame.opacity*.45)));
        graphics.enableScissor(x+1, y+1, x+width-1, y+height-1);
        if (frame.previous == frame.state) drawState(graphics, frame.state, frame.repetitions, x, y, width, frame.opacity, 0, frame.progress);
        else {
            drawState(graphics, frame.previous, frame.previousRepetitions, x, y, width, frame.opacity*(1-frame.mix), (int) (-4*frame.mix), frame.previousProgress);
            drawState(graphics, frame.state, frame.repetitions, x, y, width, frame.opacity*frame.mix, (int) (4*(1-frame.mix)), frame.progress);
        }
        graphics.disableScissor();
        if (frame.state == State.SAVE) {
            int inset = motion.animated ? (int) (5*(1-frame.mix)) : 0;
            int color = LumiTheme.withOpacity(LumiTheme.ACCENT, (float) (frame.opacity*.8));
            for (int side = 0; side < 2; side++) {
                int edge = side == 0 ? x+inset : x+width-inset-1;
                int left = side == 0 ? edge : edge-7;
                graphics.fill(edge, y+inset, edge+1, y+8+inset, color);
                graphics.fill(left, y+inset, left+8, y+inset+1, color);
            }
        }
        return true;
    }

    private static void drawState(GuiGraphics graphics, State state, int repetitions, int x, int y, int width,
            double opacity, int shift, double progress) {
        if (opacity < .04) return;
        int accent = state == State.ERROR ? LumiTheme.DANGER : state == State.IDLE ? LumiTheme.MUTED : LumiTheme.ACCENT;
        int detailWidth = state.height == 24
                ? Math.min(Minecraft.getInstance().font.width(state.detail), Math.max(0, (width-20)/2)) : 0;
        String title = repetitions > 1 && (state == State.UNDO || state == State.REDO)
                ? repetitions + (state == State.UNDO ? " edits undone" : " edits restored") : state.title;
        text(graphics, "Preview · " + title, x+10+shift, y+8,
                width-20-(detailWidth > 0 ? detailWidth+8 : 0), LumiTheme.TEXT, opacity);
        if (state.height == 24) {
            text(graphics, state.detail, x+width-detailWidth-10+shift, y+8, detailWidth, accent, opacity);
        } else text(graphics, state.detail, x+10+shift, y+23, width-20, accent, opacity);
        if (state.progress && progress > 0) {
            float barOpacity = (float) (opacity * Math.min(1, progress * 8));
            graphics.fill(x+10, y+43, x+width-10, y+45, LumiTheme.withOpacity(LumiTheme.CHIP, barOpacity));
            graphics.fill(x+10, y+43, x+10+(int) ((width-20)*progress), y+45,
                    LumiTheme.withOpacity(accent, barOpacity));
        }
    }

    private static void text(GuiGraphics graphics, String text, int x, int y, int width, int color, double opacity) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, font.plainSubstrByWidth(text, Math.max(0, width)), x, y,
                LumiTheme.withOpacity(color, (float) opacity), false);
    }

    static final class Motion {
        private State state = State.IDLE, previous = State.IDLE;
        private long changed, updated;
        private int repetitions = 1, previousRepetitions = 1;
        private double fromHeight = 24, fromOpacity, previousProgress;
        private boolean visible;
        boolean animated = true;

        void change(State next, long now) {
            if (visible && next == state && (next == State.UNDO || next == State.REDO)
                    && now-updated < next.holdMillis * 1_000_000L) {
                repetitions++;
                updated = now;
                return;
            }
            var before = frame(now);
            previous = state; fromHeight = before.height; fromOpacity = before.opacity;
            previousProgress = before.progress; previousRepetitions = repetitions;
            repetitions = 1; state = next; changed = updated = now; visible = true;
        }

        void reset() {
            visible = false; state = previous = State.IDLE;
            repetitions = previousRepetitions = 1; fromHeight = 24; fromOpacity = 0;
        }

        Frame frame(long now) {
            double elapsed = Math.max(0, (now-changed)/1_000_000_000.0);
            double linear = Math.clamp(elapsed/.3, 0, 1);
            double mix = animated ? 1-Math.pow(1-linear, 3) : 1;
            return new Frame(previous, state, mix, fromHeight+(state.height-fromHeight)*mix,
                    visible ? fromOpacity+(1-fromOpacity)*mix : 0,
                    state.progress ? Math.clamp((elapsed-.45)/2.5, 0, 1) : 0, previousProgress,
                    repetitions, previousRepetitions);
        }
    }

    record Frame(State previous, State state, double mix, double height,
            double opacity, double progress, double previousProgress,
            int repetitions, int previousRepetitions) { }
}
