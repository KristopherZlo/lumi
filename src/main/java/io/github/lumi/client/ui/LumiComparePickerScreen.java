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
    private static final int NARROW_COLUMN_GAP = 22;
    private static final int ROW_HEIGHT = 42;
    private static final int ROW_STRIDE = 46;
    private static final int PREVIEW_WIDTH = 44;
    private static final int PREVIEW_HEIGHT = 30;
    private static final int COMPACT_PREVIEW_WIDTH = 24;
    private static final int COMPACT_PREVIEW_HEIGHT = 16;
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
    private int leftScroll;
    private int rightScroll;

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
                (branch, zone, offset, limit, query) ->
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
            leftHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS);
            rightHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS);
        }
        renderedLeftPage = leftHistory.page().orElse(null);
        renderedRightPage = rightHistory.page().orElse(null);
        addColumnButtons(true);
        addColumnButtons(false);
        int footerY = layout.y() + layout.height() - 28;
        LumiLegacyButton submit = addLegacyIconButton(
                layout.x() + layout.width() - 42, footerY, "eye-open",
                Component.translatable("luma.action.see_changes"),
                this::compareSelected, LumiLegacyButton.Kind.PRIMARY);
        submit.active = target().isPresent();
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
        int scroll = scroll(left);
        int end = Math.min(scroll + rows, versions.size());
        for (int index = scroll; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = rowsY() + index * ROW_STRIDE;
            rowY -= scroll * ROW_STRIDE;
            boolean selected = version.equals(
                    left ? leftSelection : rightSelection);
            boolean compact = compactCards();
            addLegacyButton(
                    compact ? x + 6 : x + width - 58,
                    compact ? rowY + 22 : rowY + 12,
                    compact ? Math.max(1, width - 12) : 50,
                    Component.translatable(selected
                            ? "luma.compare.selected_save"
                            : "luma.compare.select_save"),
                    () -> select(left, version),
                    selected
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
        }
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
        setScroll(left, 0);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(
                    graphics, layout.x(), layout.y(), layout.width(), layout.height());
            renderPageHeader(graphics, layout.x(), layout.y(), layout.width(), title,
                    Component.translatable("luma.compare.pick_help"));
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
            Component message = error.isEmpty()
                    ? Component.translatable("luma.history.empty")
                    : Component.literal(error);
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(message.getString(), Math.max(1, width - 4)),
                    x + width / 2, rowsY() + 8, LegacyLumiTheme.MUTED);
            return;
        }
        int rows = visibleRows();
        int scroll = scroll(left);
        int end = Math.min(scroll + rows, versions.size());
        for (int index = scroll; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            renderCard(
                    graphics, version, x,
                    rowsY() + (index - scroll) * ROW_STRIDE, width,
                    version.equals(left ? leftSelection : rightSelection));
        }
        renderLegacyScrollbar(
                graphics, x + width - 3, rowsY(), rowsHeight(),
                versions.size(), rows, scroll);
    }

    private void renderCard(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x,
            int y,
            int width,
            boolean selected) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, ROW_HEIGHT,
                LegacyLumiTheme.PANEL,
                selected ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.PANEL_BORDER);
        boolean compact = compactCards();
        int previewWidth = compact ? COMPACT_PREVIEW_WIDTH : PREVIEW_WIDTH;
        int previewHeight = compact ? COMPACT_PREVIEW_HEIGHT : PREVIEW_HEIGHT;
        drawPreview(graphics, version, x + 5, y + (compact ? 3 : 6),
                previewWidth, previewHeight);
        int textX = x + previewWidth + 11;
        int textWidth = Math.max(0,
                width - previewWidth - (compact ? 16 : 80));
        graphics.drawString(font,
                font.plainSubstrByWidth(version.message(), textWidth),
                textX, y + 6, LegacyLumiTheme.TEXT, false);
        if (!compact) {
            String metadata = version.author() + " · " + DATE_FORMAT.format(
                    Instant.ofEpochMilli(version.timestampMillis()));
            graphics.drawString(font,
                    font.plainSubstrByWidth(metadata, textWidth),
                    textX, y + 20, LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x, int y, int width, int height) {
        var texture = previews.texture(
                snapshot.dimensionId(), version.id()).orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, width, height,
                    texture.width(), texture.height(),
                    texture.width(), texture.height());
            return;
        }
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.INSET_BORDER);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, NO_PREVIEW_ICON,
                x + (width - 12) / 2,
                y + (height - 12) / 2,
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
        return leftX() + columnWidth() + columnGap();
    }

    private int columnWidth() {
        return columnWidth(layout.width());
    }

    static int columnWidth(int layoutWidth) {
        return Math.max(1, (layoutWidth - 32 - columnGap(layoutWidth)) / 2);
    }

    private int columnGap() {
        return columnGap(layout.width());
    }

    private static int columnGap(int layoutWidth) {
        return layoutWidth < 360 ? NARROW_COLUMN_GAP : COLUMN_GAP;
    }

    private boolean compactCards() {
        return columnWidth() < 150;
    }

    private int dividerX() {
        return leftX() + columnWidth() + columnGap() / 2;
    }

    private int rowsY() {
        return layout.y() + 86;
    }

    private int rowsHeight() {
        return layout.y() + layout.height() - 30 - rowsY();
    }

    private int visibleRows() {
        return visibleRows(layout.height());
    }

    static int visibleRows(int layoutHeight) {
        int available = layoutHeight - 114;
        if (available < ROW_HEIGHT) return 0;
        return Math.min(MAX_ROWS,
                1 + (available - ROW_HEIGHT) / ROW_STRIDE);
    }

    private WorkspaceHistoryController history(boolean left) {
        return left ? leftHistory : rightHistory;
    }

    private List<HistorySnapshotPayload.Version> versions(boolean left) {
        return history(left).versions();
    }

    private int scroll(boolean left) {
        return left ? leftScroll : rightScroll;
    }

    private void setScroll(boolean left, int value) {
        if (left) leftScroll = value;
        else rightScroll = value;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        boolean left = x < dividerX();
        if (y >= rowsY() && y < layout.y() + layout.height() - 28
                && x >= (left ? leftX() : dividerX())
                && x < (left ? dividerX() : layout.x() + layout.width())) {
            List<HistorySnapshotPayload.Version> versions = versions(left);
            int maximum = Math.max(0, versions.size() - visibleRows());
            int replacement = Math.max(0, Math.min(maximum,
                    scroll(left) + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll(left)) {
                setScroll(left, replacement);
                rebuildWidgets();
            } else if (verticalAmount < 0 && history(left).hasNext()) {
                history(left).next();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
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
