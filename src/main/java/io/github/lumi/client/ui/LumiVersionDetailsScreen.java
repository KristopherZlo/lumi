package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Details and display metadata for one saved version. */
public final class LumiVersionDetailsScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 540;
    private static final int PANEL_HEIGHT = 314;
    private static final int PREVIEW_WIDTH = 240;
    private static final int PREVIEW_HEIGHT = 135;
    private static final int MAX_PREVIEW_ZOOM = 4;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final String dimensionId;
    private final HistorySnapshotPayload.Version version;
    private final ClientVersionPreviewStore previews;
    private final Runnable restore;
    private final Optional<Runnable> compareToParent;
    private final Optional<Runnable> createBranch;
    private final Runnable delete;
    private final Consumer<VersionTags> updateTags;
    private final Consumer<String> rename;
    private final boolean readOnly;
    private String displayedName;
    private VersionTags displayedTags;
    private int previewZoom = 1;
    private int previewPanX;
    private int previewPanY;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public LumiVersionDetailsScreen(
            Screen parent,
            String dimensionId,
            HistorySnapshotPayload.Version version,
            ClientVersionPreviewStore previews,
            Runnable restore,
            Optional<Runnable> compareToParent,
            Optional<Runnable> createBranch,
            Runnable delete,
            Consumer<VersionTags> updateTags,
            Consumer<String> rename) {
        this(parent, dimensionId, version, previews, restore, compareToParent,
                createBranch, delete, updateTags, rename,
                false);
    }

    public LumiVersionDetailsScreen(
            Screen parent,
            String dimensionId,
            HistorySnapshotPayload.Version version,
            ClientVersionPreviewStore previews,
            Runnable restore,
            Optional<Runnable> compareToParent,
            Optional<Runnable> createBranch,
            Runnable delete,
            Consumer<VersionTags> updateTags,
            Consumer<String> rename,
            boolean readOnly) {
        super(parent, Component.translatable(
                "luma.screen.save_details.title", VersionText.name(version)));
        this.parent = parent;
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.version = Objects.requireNonNull(version, "version");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.compareToParent = Objects.requireNonNull(
                compareToParent, "compareToParent");
        this.createBranch = Objects.requireNonNull(createBranch, "createBranch");
        this.delete = Objects.requireNonNull(delete, "delete");
        this.updateTags = Objects.requireNonNull(updateTags, "updateTags");
        this.rename = Objects.requireNonNull(rename, "rename");
        this.readOnly = readOnly;
        this.displayedName = VersionText.name(version);
        this.displayedTags = version.tags();
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        addActions(panelY + actionOffset(panelWidth, panelHeight));

        int renameX = Math.max(
                panelX + 20,
                navigationControlX(panelX, panelWidth) - 8 - 26);
        if (!readOnly && !VersionText.immutable(version)) {
            addIconButton(renameX, panelY + 8, "edit-text",
                    Component.translatable("luma.action.rename_save"),
                    this::editName, LumiButton.Kind.NORMAL);
        }

        if (!readOnly && !VersionText.immutable(version)) {
            addIconButton(
                    panelX + panelWidth - 44,
                    panelY + (compact(panelWidth, panelHeight) ? 78 : 139),
                    "tags",
                    Component.translatable("luma.action.edit_tags"),
                    this::editTags, LumiButton.Kind.NORMAL);
        }
        addPreviewControls(panelY
                + previewControlsOffset(panelWidth, panelHeight));
    }

    private void addActions(int y) {
        int gap = 8;
        int width = 26 * 4 + gap * 3;
        int x = panelX + (panelWidth - width) / 2;
        LumiButton restoreButton = addIconButton(x, y, "rollback",
                Component.translatable("luma.action.restore"),
                restore, LumiButton.Kind.PRIMARY);
        restoreButton.active = !readOnly;
        LumiButton branch = addIconButton(x + 26 + gap, y, "branch",
                Component.translatable("luma.save_details.create_idea"),
                () -> createBranch.ifPresent(Runnable::run),
                LumiButton.Kind.NORMAL);
        branch.active = createBranch.isPresent();
        LumiButton compare = addIconButton(x + (26 + gap) * 2, y, "see-changes",
                Component.translatable("luma.action.see_changes"),
                () -> compareToParent.ifPresent(Runnable::run),
                LumiButton.Kind.NORMAL);
        compare.active = compareToParent.isPresent();
        LumiButton remove = addIconButton(
                x + (26 + gap) * 3, y, "trash",
                Component.translatable("luma.action.delete_save"),
                delete, LumiButton.Kind.DANGER);
        remove.active = !readOnly && !VersionText.immutable(version);
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderWindow(
                    graphics, panelX, panelY, panelWidth, panelHeight);
            int renameX = navigationControlX(panelX, panelWidth) - 8 - 26;
            int titleRight = readOnly || VersionText.immutable(version)
                    ? navigationControlX(panelX, panelWidth) - 8 : renameX - 8;
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            displayedName, Math.max(1, titleRight - panelX - 20)),
                    panelX + 20, panelY + 14, LumiTheme.TEXT, false);
            renderPreview(graphics);
            if (compact(panelWidth, panelHeight)) {
                renderCompactMetadata(graphics);
            } else {
                renderFullMetadata(graphics);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderFullMetadata(GuiGraphics graphics) {
        int metadataX = panelX + 280;
        int metadataWidth = Math.max(0, panelWidth - 300);
        graphics.drawString(font,
                Component.translatable(
                        "luma.save_details.summary_help",
                        DATE_FORMAT.format(
                                Instant.ofEpochMilli(version.timestampMillis()))),
                metadataX, panelY + 58, LumiTheme.MUTED, false);
        graphics.drawString(font,
                Component.translatable(
                        "luma.save_details.raw_info_author", version.author()),
                metadataX, panelY + 82, LumiTheme.TEXT, false);
        graphics.drawString(font,
                Component.translatable(
                        "luma.save_details.raw_info_type", version.kind().name()),
                metadataX, panelY + 102, LumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.save.tags_title"),
                metadataX, panelY + 126, LumiTheme.MUTED, false);
        String tags = displayedTags.isEmpty()
                ? Component.translatable("luma.history.tags_empty").getString()
                : displayedTags.display();
        graphics.drawString(font,
                font.plainSubstrByWidth(tags, Math.max(0, metadataWidth - 30)),
                metadataX, panelY + 147, LumiTheme.TEXT, false);
        graphics.drawString(font,
                Component.translatable("luma.save_details.raw_info_title"),
                metadataX, panelY + 184, LumiTheme.MUTED, false);
        graphics.drawWordWrap(font,
                Component.translatable(
                        "luma.save_details.raw_info_id", version.id().hex()),
                metadataX, panelY + 202, metadataWidth,
                LumiTheme.TEXT);
    }

    private void renderCompactMetadata(GuiGraphics graphics) {
        int previewWidth = previewWidth(panelWidth, panelHeight);
        int metadataX = panelX + previewWidth + 32;
        int metadataWidth = Math.max(1, panelX + panelWidth - 20 - metadataX);
        drawMetadataLine(graphics,
                Component.translatable(
                        "luma.save_details.summary_help",
                        DATE_FORMAT.format(
                                Instant.ofEpochMilli(version.timestampMillis()))),
                metadataX, panelY + 43, metadataWidth);
        drawMetadataLine(graphics,
                Component.translatable(
                        "luma.save_details.raw_info_author", version.author()),
                metadataX, panelY + 57, metadataWidth);
        drawMetadataLine(graphics,
                Component.translatable(
                        "luma.save_details.raw_info_type", version.kind().name()),
                metadataX, panelY + 70, metadataWidth);
        String tags = displayedTags.isEmpty()
                ? Component.translatable("luma.history.tags_empty").getString()
                : displayedTags.display();
        drawMetadataLine(graphics,
                Component.translatable("luma.save.tags_title")
                        .append(": " + tags),
                metadataX, panelY + 84, Math.max(1, metadataWidth - 30));
        drawMetadataLine(graphics,
                Component.translatable(
                        "luma.save_details.raw_info_id", version.id().hex()),
                metadataX, panelY + 98, metadataWidth);
    }

    private void drawMetadataLine(
            GuiGraphics graphics, Component value,
            int x, int y, int availableWidth) {
        graphics.drawString(font,
                font.plainSubstrByWidth(value.getString(), availableWidth),
                x, y, LumiTheme.TEXT, false);
    }

    private void renderPreview(GuiGraphics graphics) {
        int x = panelX + 20;
        int y = panelY + previewOffset(panelWidth, panelHeight);
        int previewWidth = previewWidth(panelWidth, panelHeight);
        int previewHeight = previewHeight(panelWidth, panelHeight);
        var texture = previews.texture(dimensionId, version.id()).orElse(null);
        if (texture == null) {
            LumiTheme.outlined(
                    graphics, x, y, previewWidth, previewHeight,
                    LumiTheme.INSET, LumiTheme.INSET_BORDER);
            LumiPreviewRenderer.drawPlaceholder(
                    graphics, x, y, previewWidth, previewHeight,
                    previews.isLoading(dimensionId, version.id()));
            return;
        }
        int sourceWidth = Math.max(1, texture.width() / previewZoom);
        int sourceHeight = Math.max(1, texture.height() / previewZoom);
        clampPreviewPan(texture.width(), texture.height(), sourceWidth, sourceHeight);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, texture.id(),
                x, y, previewPanX, previewPanY, previewWidth, previewHeight,
                sourceWidth, sourceHeight,
                texture.width(), texture.height());
    }

    private void addPreviewControls(int y) {
        addIconButton(panelX + 20, y, "minus",
                Component.translatable("luma.action.zoom_out"),
                () -> zoomPreview(-1), LumiButton.Kind.NORMAL);
        addIconButton(panelX + 48, y, "plus",
                Component.translatable("luma.action.zoom_in"),
                () -> zoomPreview(1), LumiButton.Kind.NORMAL);
        addIconButton(panelX + 104, y, "chevron-left",
                Component.translatable("luma.action.back"),
                () -> panPreview(-1, 0), LumiButton.Kind.NORMAL);
        addIconButton(panelX + 132, y, "chevron-up",
                Component.translatable("luma.action.preview_pan_up"),
                () -> panPreview(0, -1), LumiButton.Kind.NORMAL);
        addIconButton(panelX + 160, y, "chevron-down",
                Component.translatable("luma.action.preview_pan_down"),
                () -> panPreview(0, 1), LumiButton.Kind.NORMAL);
        addIconButton(panelX + 188, y, "chevron-right",
                Component.translatable("luma.action.next"),
                () -> panPreview(1, 0), LumiButton.Kind.NORMAL);
    }

    static LumiModalLayout fitPanel(int screenWidth, int screenHeight) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 16));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LumiModalLayout(
                Math.max(0, (screenWidth - panelWidth) / 2),
                Math.max(0, (screenHeight - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    static int actionOffset(int panelWidth, int panelHeight) {
        return panelHeight - (compact(panelWidth, panelHeight) ? 26 : 30);
    }

    static int previewControlsOffset(int panelWidth, int panelHeight) {
        return compact(panelWidth, panelHeight)
                ? actionOffset(panelWidth, panelHeight) - 24
                : 194;
    }

    static int previewOffset(int panelWidth, int panelHeight) {
        return compact(panelWidth, panelHeight) ? 40 : 52;
    }

    static int previewWidth(int panelWidth, int panelHeight) {
        if (!compact(panelWidth, panelHeight)) return PREVIEW_WIDTH;
        return Math.min(PREVIEW_WIDTH,
                Math.max(96, (panelWidth - 56) / 2));
    }

    static int previewHeight(int panelWidth, int panelHeight) {
        int previewWidth = previewWidth(panelWidth, panelHeight);
        int available = previewControlsOffset(panelWidth, panelHeight)
                - previewOffset(panelWidth, panelHeight) - 6;
        return Math.min(PREVIEW_HEIGHT,
                Math.max(1, Math.min(previewWidth * 9 / 16, available)));
    }

    private static boolean compact(int panelWidth, int panelHeight) {
        return panelWidth < 500 || panelHeight < PANEL_HEIGHT;
    }

    private void zoomPreview(int delta) {
        previewZoom = Math.max(1, Math.min(MAX_PREVIEW_ZOOM, previewZoom + delta));
    }

    private void panPreview(int horizontal, int vertical) {
        previews.texture(dimensionId, version.id()).ifPresent(texture -> {
            int stepX = Math.max(1, texture.width() / previewZoom / 4);
            int stepY = Math.max(1, texture.height() / previewZoom / 4);
            previewPanX += horizontal * stepX;
            previewPanY += vertical * stepY;
        });
    }

    private void clampPreviewPan(
            int textureWidth, int textureHeight, int sourceWidth, int sourceHeight) {
        previewPanX = Math.max(0, Math.min(textureWidth - sourceWidth, previewPanX));
        previewPanY = Math.max(0, Math.min(textureHeight - sourceHeight, previewPanY));
    }

    private void editTags() {
        minecraft.setScreen(new LumiVersionTagsScreen(
                this, displayedTags, replacement -> {
                    updateTags.accept(replacement);
                    displayedTags = replacement;
                }));
    }

    private void editName() {
        minecraft.setScreen(new LumiVersionRenameScreen(
                this, displayedName, replacement -> {
                    rename.accept(replacement);
                    displayedName = replacement;
                }));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
