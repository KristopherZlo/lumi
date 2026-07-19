package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Independent left/right save columns that dispatch Compare into the world. */
public final class LumiComparePickerScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 5;
    private static final int COLUMN_GAP = 42;
    private static final int ROW_HEIGHT = 42;
    private static final int ROW_STRIDE = 46;
    private static final int PREVIEW_WIDTH = 44;
    private static final int PREVIEW_HEIGHT = 30;
    private static final Identifier CENTER_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/icons/see-changes.png");
    private static final Identifier NO_PREVIEW_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/new-icons/image.png");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final HistorySnapshotPayload snapshot;
    private final ClientVersionPreviewStore previews;
    private final Consumer<VersionCompareController.Target> compare;
    private final VersionCompareController controller = new VersionCompareController();
    private final WorkspaceHistoryController leftHistory;
    private final WorkspaceHistoryController rightHistory;
    private LegacyModalLayout layout;
    private HistoryPagePayload renderedLeftPage;
    private HistoryPagePayload renderedRightPage;
    private HistorySnapshotPayload.Version leftSelection;
    private HistorySnapshotPayload.Version rightSelection;

    public LumiComparePickerScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            ClientVersionPreviewStore previews,
            ClientHistoryPageStore historyPages,
            PageRequester requestPage,
            Consumer<VersionCompareController.Target> compare) {
        super(parent, Component.translatable("luma.compare.pick_title"),
                LegacyProjectTab.COMPARE);
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.compare = Objects.requireNonNull(compare, "compare");
        Objects.requireNonNull(historyPages, "historyPages");
        Objects.requireNonNull(requestPage, "requestPage");
        leftHistory = history(
                historyPages, requestPage, ClientHistoryPageStore.createChannel());
        rightHistory = history(
                historyPages, requestPage, ClientHistoryPageStore.createChannel());
    }

    private WorkspaceHistoryController history(
            ClientHistoryPageStore pages,
            PageRequester requester,
            ClientHistoryPageStore.Channel channel) {
        return new WorkspaceHistoryController(
                snapshot, pages, channel,
                (branch, zone, offset, limit) ->
                        requester.request(channel, branch, zone, offset, limit));
    }

    @Override
    public void tick() {
        super.tick();
        HistoryPagePayload latestLeft = leftHistory.page().orElse(null);
        HistoryPagePayload latestRight = rightHistory.page().orElse(null);
        if (!Objects.equals(renderedLeftPage, latestLeft)
                || !Objects.equals(renderedRightPage, latestRight)) {
            renderedLeftPage = latestLeft;
            renderedRightPage = latestRight;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout shell = pageLayout();
        layout = new LegacyModalLayout(
                shell.contentX(), shell.windowY(),
                shell.contentWidth(), shell.windowHeight());
        int rows = visibleRows();
        if (rows > 0) {
            leftHistory.ensurePageSize(rows);
            rightHistory.ensurePageSize(rows);
        }
        renderedLeftPage = leftHistory.page().orElse(null);
        renderedRightPage = rightHistory.page().orElse(null);
        addColumnButtons(true);
        addColumnButtons(false);
        int footerY = layout.y() + layout.height() - 28;
        int dividerX = dividerX();
        LumiLegacyButton submit = addLegacyIconButton(
                dividerX - 13, footerY, "eye-open",
                Component.translatable("luma.action.see_changes"),
                this::compareSelected, LumiLegacyButton.Kind.PRIMARY);
        submit.active = target().isPresent();
        addLegacyIconButton(
                layout.x() + layout.width() - 42, footerY, "close",
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addColumnButtons(boolean left) {
        WorkspaceHistoryController history = history(left);
        List<HistorySnapshotPayload.Version> versions = versions(left);
        int x = left ? leftX() : rightX();
        int width = columnWidth();
        addRenderableWidget(new LumiLegacyButton(
                x, layout.y() + 66, width, 18,
                Component.literal(history.branch().value()),
                ignored -> changeBranch(left), LumiLegacyButton.Kind.NORMAL));
        int rows = visibleRows();
        int end = Math.min(rows, versions.size());
        for (int index = 0; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = rowsY() + index * ROW_STRIDE;
            boolean selected = version.equals(
                    left ? leftSelection : rightSelection);
            addLegacyButton(
                    x + width - 58, rowY + 12, 50,
                    Component.translatable(selected
                            ? "luma.compare.selected_save"
                            : "luma.compare.select_save"),
                    () -> select(left, version),
                    selected
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
        }
        int footerY = layout.y() + layout.height() - 28;
        LumiLegacyButton previous = addLegacyIconButton(
                x, footerY, "chevron-left", Component.literal("<"),
                () -> changePage(left, -1), LumiLegacyButton.Kind.NORMAL);
        previous.active = history.hasPrevious();
        LumiLegacyButton next = addLegacyIconButton(
                x + 32, footerY, "chevron-right", Component.literal(">"),
                () -> changePage(left, 1), LumiLegacyButton.Kind.NORMAL);
        next.active = history.hasNext();
    }

    private void select(
            boolean left, HistorySnapshotPayload.Version version) {
        if (left) {
            leftSelection = version;
        } else {
            rightSelection = version;
        }
        rebuildWidgets();
    }

    private void changeBranch(boolean left) {
        history(left).nextBranch(snapshot.branches());
        if (left) {
            leftSelection = null;
        } else {
            rightSelection = null;
        }
        rebuildWidgets();
    }

    private void compareSelected() {
        target().ifPresent(selected -> {
            minecraft.setScreen(null);
            compare.accept(selected);
        });
    }

    private java.util.Optional<VersionCompareController.Target> target() {
        return controller.target(leftSelection, rightSelection);
    }

    private void changePage(boolean left, int delta) {
        WorkspaceHistoryController history = history(left);
        if (delta < 0) {
            history.previous();
        } else {
            history.next();
        }
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(
                    graphics, layout.x(), layout.y(), layout.width(), layout.height());
            graphics.drawString(font, title,
                    layout.x() + 16, layout.y() + 14,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.compare.pick_help"),
                    layout.x() + 16, layout.y() + 32,
                    LegacyLumiTheme.MUTED, false);
            renderColumn(graphics, true);
            renderColumn(graphics, false);
            renderDivider(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderColumn(GuiGraphics graphics, boolean left) {
        int x = left ? leftX() : rightX();
        int width = columnWidth();
        graphics.drawCenteredString(font,
                Component.translatable(left
                        ? "luma.compare.left_column"
                        : "luma.compare.right_column"),
                x + width / 2, layout.y() + 52, LegacyLumiTheme.ACCENT);
        List<HistorySnapshotPayload.Version> versions = versions(left);
        if (versions.isEmpty()) {
            String error = history(left).error();
            graphics.drawCenteredString(font,
                    error.isEmpty()
                            ? Component.translatable("luma.history.empty")
                            : Component.literal(error),
                    x + width / 2, rowsY() + 8, LegacyLumiTheme.MUTED);
            return;
        }
        int rows = visibleRows();
        int end = Math.min(rows, versions.size());
        for (int index = 0; index < end; index++) {
            renderCard(
                    graphics, versions.get(index), x,
                    rowsY() + index * ROW_STRIDE, width);
        }
    }

    private void renderCard(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x,
            int y,
            int width) {
        renderLegacyPanel(graphics, x, y, width, ROW_HEIGHT);
        drawPreview(graphics, version, x + 5, y + 6);
        int textX = x + PREVIEW_WIDTH + 11;
        int textWidth = Math.max(0, width - PREVIEW_WIDTH - 80);
        graphics.drawString(font,
                font.plainSubstrByWidth(version.message(), textWidth),
                textX, y + 6, LegacyLumiTheme.TEXT, false);
        String metadata = version.author() + " · " + DATE_FORMAT.format(
                Instant.ofEpochMilli(version.timestampMillis()));
        graphics.drawString(font,
                font.plainSubstrByWidth(metadata, textWidth),
                textX, y + 20, LegacyLumiTheme.MUTED, false);
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x,
            int y) {
        var texture = previews.texture(
                snapshot.dimensionId(), version.id()).orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    texture.width(), texture.height(),
                    texture.width(), texture.height());
            return;
        }
        LegacyLumiTheme.outlined(
                graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.INSET_BORDER);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, NO_PREVIEW_ICON,
                x + (PREVIEW_WIDTH - 12) / 2,
                y + (PREVIEW_HEIGHT - 12) / 2,
                0, 0, 12, 12, 24, 24, 24, 24);
    }

    private void renderDivider(GuiGraphics graphics) {
        int x = dividerX();
        int center = layout.y() + layout.height() / 2;
        graphics.fill(x, layout.y() + 50, x + 1, center - 12,
                LegacyLumiTheme.PANEL_BORDER);
        graphics.fill(x, center + 12, x + 1,
                layout.y() + layout.height() - 38,
                LegacyLumiTheme.PANEL_BORDER);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, CENTER_ICON,
                x - 6, center - 6, 0, 0, 12, 12,
                24, 24, 24, 24);
    }

    private int leftX() {
        return layout.x() + 16;
    }

    private int rightX() {
        return leftX() + columnWidth() + COLUMN_GAP;
    }

    private int columnWidth() {
        return Math.max(1, (layout.width() - 32 - COLUMN_GAP) / 2);
    }

    private int dividerX() {
        return leftX() + columnWidth() + COLUMN_GAP / 2;
    }

    private int rowsY() {
        return layout.y() + 88;
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, Math.max(0, (layout.height() - 130) / ROW_STRIDE));
    }

    private WorkspaceHistoryController history(boolean left) {
        return left ? leftHistory : rightHistory;
    }

    private List<HistorySnapshotPayload.Version> versions(boolean left) {
        return history(left).versions();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @FunctionalInterface
    public interface PageRequester {
        UUID request(
                ClientHistoryPageStore.Channel channel,
                BranchName branch,
                Optional<UUID> zoneId,
                int offset,
                int limit);
    }
}
