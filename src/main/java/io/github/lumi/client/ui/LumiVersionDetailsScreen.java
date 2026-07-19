package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Legacy-style details and display metadata for one saved version. */
public final class LumiVersionDetailsScreen extends LumiLegacyModalScreen {
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
    private final Optional<Runnable> amend;
    private final Optional<Runnable> partialRestore;
    private final Runnable delete;
    private final Consumer<VersionTags> updateTags;
    private final Consumer<String> rename;
    private final boolean readOnly;
    private String displayedName;
    private boolean editingName;
    private EditBox nameEditor;
    private String nameError = "";
    private VersionTags displayedTags;
    private int previewZoom = 1;
    private int previewPanX;
    private int previewPanY;
    private int panelX;
    private int panelY;

    public LumiVersionDetailsScreen(
            Screen parent,
            String dimensionId,
            HistorySnapshotPayload.Version version,
            ClientVersionPreviewStore previews,
            Runnable restore,
            Optional<Runnable> compareToParent,
            Optional<Runnable> createBranch,
            Optional<Runnable> amend,
            Optional<Runnable> partialRestore,
            Runnable delete,
            Consumer<VersionTags> updateTags,
            Consumer<String> rename) {
        this(parent, dimensionId, version, previews, restore, compareToParent,
                createBranch, amend, partialRestore, delete, updateTags, rename,
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
            Optional<Runnable> amend,
            Optional<Runnable> partialRestore,
            Runnable delete,
            Consumer<VersionTags> updateTags,
            Consumer<String> rename,
            boolean readOnly) {
        super(parent, Component.translatable(
                "luma.screen.save_details.title", version.message()));
        this.parent = parent;
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.version = Objects.requireNonNull(version, "version");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.compareToParent = Objects.requireNonNull(
                compareToParent, "compareToParent");
        this.createBranch = Objects.requireNonNull(createBranch, "createBranch");
        this.amend = Objects.requireNonNull(amend, "amend");
        this.partialRestore = Objects.requireNonNull(
                partialRestore, "partialRestore");
        this.delete = Objects.requireNonNull(delete, "delete");
        this.updateTags = Objects.requireNonNull(updateTags, "updateTags");
        this.rename = Objects.requireNonNull(rename, "rename");
        this.readOnly = readOnly;
        this.displayedName = version.message();
        this.displayedTags = version.tags();
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int buttonWidth = Math.max(52, (panelWidth - 40) / 3);
        int buttonY = panelY + PANEL_HEIGHT - 30;
        LumiLegacyButton restoreButton = addLegacyButton(
                panelX + 16, buttonY, buttonWidth,
                Component.translatable("luma.action.restore"),
                restore, LumiLegacyButton.Kind.PRIMARY);
        restoreButton.active = !readOnly;
        LumiLegacyButton compare = addLegacyButton(
                panelX + 20 + buttonWidth, buttonY, buttonWidth,
                Component.translatable("luma.action.compare"),
                () -> compareToParent.ifPresent(Runnable::run),
                LumiLegacyButton.Kind.NORMAL);
        compare.active = compareToParent.isPresent();
        addSecondaryActions(panelWidth, buttonY - 28);

        if (!readOnly && editingName) {
            nameEditor = new EditBox(font, panelX + 20, panelY + 12,
                    Math.max(20, panelWidth - 150), 16,
                    Component.translatable("luma.save_details.rename_title"));
            nameEditor.setMaxLength(VersionDisplayName.MAX_LENGTH);
            nameEditor.setValue(displayedName);
            addRenderableWidget(nameEditor);
            addLegacyButton(panelX + panelWidth - 122, panelY + 13, 102,
                    Component.translatable("luma.action.rename_save"),
                    this::saveName, LumiLegacyButton.Kind.PRIMARY);
        } else if (!readOnly) {
            addLegacyButton(panelX + panelWidth - 122, panelY + 13, 102,
                    Component.translatable("luma.action.rename_save"), () -> {
                        editingName = true;
                        nameError = "";
                        rebuildWidgets();
                    }, LumiLegacyButton.Kind.NORMAL);
        }

        if (!readOnly) {
            addLegacyIconButton(panelX + panelWidth - 44, panelY + 139, "tags",
                    Component.translatable("luma.action.edit_tags"),
                    this::editTags, LumiLegacyButton.Kind.NORMAL);
        }
        addPreviewControls();
    }

    private void addSecondaryActions(int panelWidth, int y) {
        LumiLegacyButton branch = addLegacyIconButton(panelX + 16, y, "branch",
                Component.translatable("luma.save_details.create_idea"),
                () -> createBranch.ifPresent(Runnable::run),
                LumiLegacyButton.Kind.NORMAL);
        branch.active = createBranch.isPresent();
        int buttonWidth = Math.max(52, (panelWidth - 100) / 2);
        LumiLegacyButton replace = addLegacyButton(panelX + 48, y, buttonWidth,
                Component.translatable("luma.action.amend_version"),
                () -> amend.ifPresent(Runnable::run), LumiLegacyButton.Kind.NORMAL);
        replace.active = amend.isPresent();
        LumiLegacyButton partial = addLegacyButton(
                panelX + 52 + buttonWidth, y, buttonWidth,
                Component.translatable("luma.action.restore_selected_area"),
                () -> partialRestore.ifPresent(Runnable::run),
                LumiLegacyButton.Kind.NORMAL);
        partial.active = partialRestore.isPresent();
        LumiLegacyButton remove = addLegacyIconButton(
                panelX + panelWidth - 40, y, "trash",
                Component.translatable("luma.action.delete_save"),
                delete, LumiLegacyButton.Kind.DANGER);
        remove.active = !readOnly;
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            if (!editingName) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(displayedName, panelWidth - 160),
                        panelX + 20, panelY + 18, LegacyLumiTheme.TEXT, false);
            }
            if (!nameError.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(nameError, panelWidth - 40),
                        panelX + 20, panelY + 38, LegacyLumiTheme.DANGER, false);
            }
            renderPreview(graphics);
            int metadataX = panelX + 280;
            int metadataWidth = Math.max(0, panelWidth - 300);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.summary_help",
                            DATE_FORMAT.format(
                                    Instant.ofEpochMilli(version.timestampMillis()))),
                    metadataX, panelY + 58, LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.raw_info_author", version.author()),
                    metadataX, panelY + 82, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.raw_info_type", version.kind().name()),
                    metadataX, panelY + 102, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, Component.translatable("luma.save.tags_title"),
                    metadataX, panelY + 126, LegacyLumiTheme.MUTED, false);
            String tags = displayedTags.isEmpty()
                    ? Component.translatable("luma.history.tags_empty").getString()
                    : displayedTags.display();
            graphics.drawString(font,
                    font.plainSubstrByWidth(tags, Math.max(0, metadataWidth - 30)),
                    metadataX, panelY + 147, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.save_details.raw_info_title"),
                    metadataX, panelY + 184, LegacyLumiTheme.MUTED, false);
            graphics.drawWordWrap(font,
                    Component.translatable(
                            "luma.save_details.raw_info_id", version.id().hex()),
                    metadataX, panelY + 202, metadataWidth,
                    LegacyLumiTheme.TEXT);
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderPreview(GuiGraphics graphics) {
        int x = panelX + 20;
        int y = panelY + 52;
        var texture = previews.texture(dimensionId, version.id()).orElse(null);
        if (texture == null) {
            LegacyLumiTheme.outlined(
                    graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.history.no_preview"),
                    x + PREVIEW_WIDTH / 2, y + PREVIEW_HEIGHT / 2 - 4,
                    LegacyLumiTheme.MUTED);
            return;
        }
        int sourceWidth = Math.max(1, texture.width() / previewZoom);
        int sourceHeight = Math.max(1, texture.height() / previewZoom);
        clampPreviewPan(texture.width(), texture.height(), sourceWidth, sourceHeight);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, texture.id(),
                x, y, previewPanX, previewPanY, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                sourceWidth, sourceHeight,
                texture.width(), texture.height());
    }

    private void addPreviewControls() {
        int y = panelY + 194;
        addLegacyIconButton(panelX + 20, y, "minus",
                Component.translatable("luma.action.zoom_out"),
                () -> zoomPreview(-1), LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 48, y, "plus",
                Component.translatable("luma.action.zoom_in"),
                () -> zoomPreview(1), LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 104, y, "chevron-left",
                Component.translatable("luma.action.back"),
                () -> panPreview(-1, 0), LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 132, y, "chevron-up",
                Component.translatable("luma.action.preview_pan_up"),
                () -> panPreview(0, -1), LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 160, y, "chevron-down",
                Component.translatable("luma.action.preview_pan_down"),
                () -> panPreview(0, 1), LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 188, y, "chevron-right",
                Component.translatable("luma.action.next"),
                () -> panPreview(1, 0), LumiLegacyButton.Kind.NORMAL);
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

    private void saveName() {
        try {
            VersionDisplayName replacement =
                    new VersionDisplayName(nameEditor.getValue());
            rename.accept(replacement.value());
            displayedName = replacement.value();
            editingName = false;
            nameError = "";
            rebuildWidgets();
        } catch (RuntimeException failed) {
            nameError = failed.getMessage() == null
                    ? "Lumi could not rename this Save" : failed.getMessage();
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
