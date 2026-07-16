package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Thin create/switch view over the server-owned workspace snapshot. */
public final class LumiWorkspacesScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;
    private static final int PAGE_SIZE = 5;
    private final Screen parent;
    private final ClientHistoryStore history;
    private final Supplier<Optional<BlockBox>> selection;
    private final WorkspaceScreenController controller;
    private final Consumer<UUID> switcher;
    private HistorySnapshotPayload snapshot;
    private EditBox name;
    private Button createWhole;
    private Button createSelection;
    private int panelX;
    private int panelY;
    private int page;
    private String error = "";

    public LumiWorkspacesScreen(
            Screen parent,
            ClientHistoryStore history,
            Supplier<Optional<BlockBox>> selection,
            WorkspaceScreenController controller,
            Consumer<UUID> switcher) {
        super(Component.translatable("luma.action.workspaces"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.switcher = Objects.requireNonNull(switcher, "switcher");
    }

    @Override
    protected void init() {
        snapshot = history.state().snapshot().orElse(null);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(
                font, contentX, panelY + 72, contentWidth - 240, 20,
                Component.translatable("luma.create_project.name"));
        name.setMaxLength(WorkspaceScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.create_project.name"));
        name.setResponder(ignored -> updateCreateButtons());
        addRenderableWidget(name);
        createWhole = addRenderableWidget(Button.builder(
                Component.translatable("luma.project.scope_world"),
                ignored -> create(Optional.empty()))
                .bounds(contentX + contentWidth - 232, panelY + 72, 112, 20).build());
        createSelection = addRenderableWidget(Button.builder(
                Component.translatable("luma.project.scope_bounds"),
                ignored -> create(selection.get()))
                .bounds(contentX + contentWidth - 112, panelY + 72, 112, 20).build());
        updateCreateButtons();
        addWorkspaceRows(panelWidth);
        addRenderableWidget(Button.builder(
                Component.literal("<"), ignored -> changePage(-1))
                .bounds(panelX + 20, panelY + 298, 28, 20).build()).active = page > 0;
        int count = snapshot == null ? 0 : snapshot.workspaces().size();
        addRenderableWidget(Button.builder(
                Component.literal(">"), ignored -> changePage(1))
                .bounds(panelX + 52, panelY + 298, 28, 20).build())
                .active = (page + 1) * PAGE_SIZE < count;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 140, panelY + 298, 120, 20).build());
    }

    private void addWorkspaceRows(int panelWidth) {
        if (snapshot == null) return;
        int start = Math.min(page * PAGE_SIZE, snapshot.workspaces().size());
        int end = Math.min(start + PAGE_SIZE, snapshot.workspaces().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.WorkspaceView workspace =
                    snapshot.workspaces().get(index);
            if (workspace.active()) continue;
            int rowY = panelY + 126 + (index - start) * 32;
            Button button = addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.open_project"),
                    ignored -> switchWorkspace(workspace.id()))
                    .bounds(panelX + panelWidth - 116, rowY + 4, 96, 20).build());
            button.active = !snapshot.operationActive();
        }
    }

    private void updateCreateButtons() {
        if (name == null || createWhole == null || createSelection == null) return;
        boolean named = !name.getValue().trim().isEmpty();
        createWhole.active = named;
        createSelection.active = named && selection.get().isPresent();
    }

    private void create(Optional<BlockBox> bounds) {
        WorkspaceScreenController.Submission submission =
                controller.create(name.getValue(), bounds);
        error = submission.error();
        if (submission.accepted()) {
            feedback("luma.status.project_created");
            minecraft.setScreen(parent);
        }
    }

    private void switchWorkspace(UUID workspaceId) {
        try {
            switcher.accept(workspaceId);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi workspace could not be switched" : failed.getMessage();
        }
    }

    private void changePage(int delta) {
        page = Math.max(0, page + delta);
        rebuildWidgets();
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    protected void setInitialFocus() {
        if (name != null) {
            setInitialFocus(name);
            name.setFocused(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, 0xffffffff);
        if (snapshot != null) {
            graphics.drawCenteredString(font,
                    Component.translatable(
                            "luma.screen.project.title", snapshot.workspaceName()),
                    width / 2, panelY + 36, 0xffaeb6c2);
        }
        graphics.drawString(font, Component.translatable("luma.screen.create_project.title"),
                panelX + 20, panelY + 56, 0xffdbe2ea, false);
        renderWorkspaceRows(graphics, panelWidth);
        if (!error.isEmpty()) {
            Component text = error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
            graphics.drawString(font, text, panelX + 88, panelY + 304,
                    0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderWorkspaceRows(GuiGraphics graphics, int panelWidth) {
        if (snapshot == null || snapshot.workspaces().isEmpty()) return;
        int start = Math.min(page * PAGE_SIZE, snapshot.workspaces().size());
        int end = Math.min(start + PAGE_SIZE, snapshot.workspaces().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.WorkspaceView workspace =
                    snapshot.workspaces().get(index);
            int rowY = panelY + 126 + (index - start) * 32;
            graphics.fill(panelX + 20, rowY,
                    panelX + panelWidth - 20, rowY + 28, 0xff20252c);
            graphics.drawString(font,
                    font.plainSubstrByWidth(workspace.name(), panelWidth - 190),
                    panelX + 28, rowY + 5, 0xfff0f3f6, false);
            Component scope = Component.translatable(workspace.bounded()
                    ? "luma.project.scope_bounds" : "luma.project.scope_world");
            graphics.drawString(font, scope, panelX + 28, rowY + 17,
                    workspace.active() ? 0xff75d69c : 0xff8f9aa8, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
