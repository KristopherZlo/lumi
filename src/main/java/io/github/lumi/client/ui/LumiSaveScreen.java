package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused Save form for the Alt+S workflow. */
public final class LumiSaveScreen extends LumiModalScreen {
    private static final int DIALOG_HEIGHT = 226;
    private static final int COMPACT_HEIGHT = 206;
    private static final int TINY_HEIGHT = 180;

    private final Screen parent;
    private final ClientHistoryStore history;
    private final SaveScreenController controller;
    private final Runnable refresh;
    private final SaveScreenController.Intent preferredIntent;
    private final String initialMessage;
    private final Consumer<UUID> previewCapture;
    private final Consumer<UUID> accepted;
    private final Consumer<String> savedName;
    private final Scope scope;
    private LumiModalLayout layout;
    private EditBox message;
    private EditBox tags;
    private LumiButton save;
    private LumiButton amend;
    private int observedPending = Integer.MIN_VALUE;
    private String error = "";

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh) {
        this(parent, history, controller, refresh,
                SaveScreenController.Intent.SAVE, "", ignored -> { }, ignored -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage) {
        this(parent, history, controller, refresh,
                preferredIntent, initialMessage, ignored -> { }, ignored -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture) {
        this(parent, history, controller, refresh, preferredIntent,
                initialMessage, previewCapture, ignored -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture,
            Consumer<UUID> accepted) {
        this(parent, history, controller, refresh, preferredIntent,
                initialMessage, previewCapture, accepted, Scope.BUILD, ignored -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture,
            Consumer<UUID> accepted,
            Scope scope) {
        this(parent, history, controller, refresh, preferredIntent,
                initialMessage, previewCapture, accepted, scope, ignored -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture,
            Consumer<UUID> accepted,
            Scope scope,
            Consumer<String> savedName) {
        super(parent, scope.title());
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.preferredIntent = Objects.requireNonNull(preferredIntent, "preferredIntent");
        this.initialMessage = Objects.requireNonNull(initialMessage, "initialMessage");
        this.previewCapture = Objects.requireNonNull(previewCapture, "previewCapture");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.savedName = Objects.requireNonNull(savedName, "savedName");
    }

    @Override
    protected void init() {
        beginScreenInit();
        layout = fitPanel(width, height);
        int x = layout.x();
        int y = layout.y();
        int actionY = y + actionOffset(layout.height());

        message = addTextField(
                x + 11, y + messageOffset(layout.height()) - 3,
                layout.width() - 22, scope.nameLabel());
        message.setMaxLength(SaveScreenController.MAX_NAME_LENGTH);
        message.setHint(scope.nameLabel());
        message.setResponder(value -> setSubmitActive(!value.trim().isEmpty()));
        message.setValue(initialMessage);

        tags = addTextField(
                x + 11, y + tagsOffset(layout.height()) - 3,
                layout.width() - 22,
                Component.translatable("luma.history.tags_input"));
        tags.setMaxLength(io.github.lumi.domain.model.VersionTags.MAX_SERIALIZED_LENGTH);
        tags.setHint(Component.translatable("luma.history.tags_input"));

        int buttonWidth = Math.max(80, (layout.width() - 18) / 2);
        save = addButton(x + 6, actionY, buttonWidth,
                scope.saveLabel(),
                () -> submit(SaveScreenController.Intent.SAVE),
                preferredIntent == SaveScreenController.Intent.SAVE
                        ? LumiButton.Kind.PRIMARY : LumiButton.Kind.NORMAL);
        amend = addButton(
                save.getX() + save.getWidth() + 4, actionY, buttonWidth,
                Component.translatable("luma.action.amend_version"),
                () -> submit(SaveScreenController.Intent.AMEND),
                preferredIntent == SaveScreenController.Intent.AMEND
                        ? LumiButton.Kind.PRIMARY : LumiButton.Kind.NORMAL);
        setSubmitActive(!initialMessage.trim().isEmpty());
        refreshPreview();
    }

    private void setSubmitActive(boolean active) {
        int pending = history.state().snapshot()
                .map(snapshot -> snapshot.pendingKeys()).orElse(0);
        observedPending = pending;
        boolean hasChanges = pending > 0;
        if (save != null) {
            save.active = active && hasChanges;
        }
        if (amend != null) {
            amend.active = active && hasChanges;
        }
    }

    @Override
    public void tick() {
        super.tick();
        int pending = history.state().snapshot()
                .map(snapshot -> snapshot.pendingKeys()).orElse(0);
        if (pending != observedPending && message != null) {
            setSubmitActive(!message.getValue().trim().isEmpty());
        }
    }

    @Override
    protected void setInitialFocus() {
        if (message != null) {
            setInitialFocus(message);
            message.setFocused(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) && save.active) {
            submit(preferredIntent);
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit(SaveScreenController.Intent intent) {
        SaveScreenController.Submission submission =
                controller.submit(message.getValue(), tags.getValue(), intent);
        error = submission.error();
        if (submission.accepted()) {
            UUID requestId = submission.requestId().orElseThrow();
            previewCapture.accept(requestId);
            accepted.accept(requestId);
            savedName.accept(message.getValue());
            minecraft.setScreen(parent);
        }
    }

    private void refreshPreview() {
        try {
            refresh.run();
            error = "";
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi preview could not refresh" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderWindow(
                graphics, layout.x(), layout.y(), layout.width(), layout.height());
        drawDialog(graphics);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        renderAnswerTooltip(graphics, render.mouseX(), render.mouseY());
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderAnswerTooltip(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (pendingChanges() != 42) return;
        String text = font.plainSubstrByWidth(
                status().getString(), layout.width() - 132);
        int answer = text.indexOf("42");
        if (answer < 0) return;
        int x = layout.x() + 12 + font.width(text.substring(0, answer));
        int y = layout.y() + 43;
        if (mouseX >= x && mouseX < x + font.width("42")
                && mouseY >= y && mouseY < y + font.lineHeight) {
            graphics.setTooltipForNextFrame(
                    Component.literal("H2G2"), mouseX, mouseY);
        }
    }

    private void drawDialog(GuiGraphics graphics) {
        int x = layout.x();
        int y = layout.y();
        boolean compact = layout.height() < COMPACT_HEIGHT;
        boolean tiny = layout.height() < TINY_HEIGHT;
        int fieldY = y + (compact ? 62 : 65);
        int tagPanelY = y + (tiny ? 98 : compact ? 106 : 137);
        graphics.drawString(font, title, x + 10, y + 12,
                LumiTheme.TEXT, false);

        LumiTheme.outlined(graphics, x + 6, y + 34,
                layout.width() - 12, 26,
                LumiTheme.STATUS, LumiTheme.STATUS_BORDER);
        Component status = status();
        int statusColor = error.isEmpty()
                ? LumiTheme.ACCENT : LumiTheme.DANGER;
        graphics.drawString(font,
                font.plainSubstrByWidth(status.getString(), layout.width() - 132),
                x + 12, y + 43, statusColor, false);

        LumiTheme.outlined(graphics, x + 6, fieldY,
                layout.width() - 12, tiny ? 34 : compact ? 42 : 67,
                LumiTheme.INSET, LumiTheme.INSET_BORDER);
        graphics.drawString(font, scope.nameLabel(),
                x + 12, fieldY + 8, LumiTheme.TEXT, false);
        if (!compact) {
            graphics.drawString(
                    font, scope.help(),
                    x + 12, fieldY + 20, LumiTheme.MUTED, false);
        }
        LumiTheme.outlined(graphics, x + 6, tagPanelY,
                layout.width() - 12, tiny ? 32 : compact ? 40 : 43,
                LumiTheme.INSET, LumiTheme.INSET_BORDER);
        graphics.drawString(font, Component.translatable("luma.save.tags_title"),
                x + 12, tagPanelY + 6, LumiTheme.TEXT, false);
    }

    static LumiModalLayout fitPanel(int screenWidth, int screenHeight) {
        return LumiModalLayout.fit(screenWidth, screenHeight, DIALOG_HEIGHT);
    }

    static int actionOffset(int panelHeight) {
        return panelHeight - 28;
    }

    static int tagsBottom(int panelHeight) {
        return tagsOffset(panelHeight) + 16;
    }

    private static int messageOffset(int panelHeight) {
        return panelHeight < TINY_HEIGHT
                ? 76 : panelHeight < COMPACT_HEIGHT ? 85 : 103;
    }

    private static int tagsOffset(int panelHeight) {
        return panelHeight < TINY_HEIGHT
                ? 110 : panelHeight < COMPACT_HEIGHT ? 127 : 156;
    }

    private Component status() {
        if (!error.isEmpty()) {
            return error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
        }
        int pending = pendingChanges();
        return pending == 0
                ? Component.translatable("luma.dashboard.pending_clean")
                : Component.translatable("luma.dashboard.workspace_pending", pending);
    }

    private int pendingChanges() {
        return history.state().snapshot()
                .map(snapshot -> snapshot.pendingKeys()).orElse(0);
    }

    public enum Scope {
        BUILD("luma.screen.save.title", "luma.action.save_build",
                "luma.save.name_input", "luma.quick_save.name_help"),
        ZONE("luma.zones.save_title", "luma.zones.save_button",
                "luma.zones.save_input", "luma.zones.save_help");

        private final String titleKey;
        private final String saveKey;
        private final String nameKey;
        private final String helpKey;

        Scope(String titleKey, String saveKey, String nameKey, String helpKey) {
            this.titleKey = titleKey;
            this.saveKey = saveKey;
            this.nameKey = nameKey;
            this.helpKey = helpKey;
        }

        Component title() { return Component.translatable(titleKey); }
        Component saveLabel() { return Component.translatable(saveKey); }
        Component nameLabel() { return Component.translatable(nameKey); }
        Component help() { return Component.translatable(helpKey); }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
