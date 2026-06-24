package io.github.luma.ui.screen;

import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.input.LumiShortcutCatalog;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.onboarding.KeyGlyphComponent;
import io.github.luma.ui.onboarding.KeyGlyphResolver;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HotkeyInfoScreen extends LumaScreen {

    private static final int MIN_DIALOG_WIDTH = 300;
    private static final int MAX_DIALOG_WIDTH = 430;
    private static final int KEY_COLUMN_WIDTH = 112;

    private final Screen parent;
    private final KeyBindingState keyBindingState = new KeyBindingState();

    public HotkeyInfoScreen(Screen parent) {
        super(Component.translatable("luma.screen.hotkeys.title"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        int dialogWidth = this.dialogWidth();
        int contentWidth = Math.max(1, dialogWidth - 16);
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(dialogWidth);
        root.child(frame);
        frame.child(this.header(contentWidth));

        FlowLayout table = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        table.gap(4);
        for (LumiShortcutCatalog.Entry entry : LumiShortcutCatalog.entries()) {
            table.child(this.shortcutRow(entry, contentWidth));
        }

        int tableHeight = Math.max(120, Math.min(260, this.height - 92));
        LumaScrollContainer<FlowLayout> scroll = LumaUi.screenScroll(
                Sizing.fill(100),
                Sizing.fixed(tableHeight),
                table
        );
        frame.child(scroll);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private FlowLayout header(int contentWidth) {
        FlowLayout header = LumaUi.titleBar();

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.value(Component.translatable("luma.hotkeys.title")).maxWidth(Math.max(1, contentWidth - 68)));
        text.child(LumaUi.caption(Component.translatable("luma.hotkeys.help")).maxWidth(Math.max(1, contentWidth - 68)));
        header.child(text);
        header.child(LumaUi.closeButton(button -> this.onClose()));
        return header;
    }

    private FlowLayout shortcutRow(LumiShortcutCatalog.Entry entry, int contentWidth) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.gap(8);

        FlowLayout keys = UIContainers.horizontalFlow(Sizing.fixed(KEY_COLUMN_WIDTH), Sizing.content());
        keys.verticalAlignment(VerticalAlignment.CENTER);
        keys.gap(3);
        this.addShortcutVisuals(keys, entry.roles());
        row.child(keys);

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        int textWidth = Math.max(120, contentWidth - KEY_COLUMN_WIDTH - 14);
        text.gap(1);
        text.child(LumaUi.value(Component.translatable(entry.labelKey())).maxWidth(textWidth));
        text.child(LumaUi.caption(Component.translatable(entry.helpKey())).maxWidth(textWidth));
        row.child(text);
        return row;
    }

    private void addShortcutVisuals(FlowLayout row, java.util.List<LumiClientKeyBindings.Role> roles) {
        for (int index = 0; index < roles.size(); index++) {
            if (index > 0) {
                row.child(LumaUi.caption(Component.literal("+")));
            }
            row.child(this.keyVisual(roles.get(index)));
        }
    }

    private UIComponent keyVisual(LumiClientKeyBindings.Role role) {
        KeyMapping key = LumiClientKeyBindings.key(role);
        return KeyGlyphResolver.resolve(key)
                .<UIComponent>map(glyph -> new KeyGlyphComponent(
                        glyph,
                        () -> this.keyBindingState.isDown(Minecraft.getInstance(), key)
                ))
                .orElseGet(() -> this.keyChip(key));
    }

    private FlowLayout keyChip(KeyMapping key) {
        Component label = key == null || key.isUnbound()
                ? Component.translatable("luma.onboarding.key_unbound")
                : key.getTranslatedKeyMessage();
        FlowLayout chip = UIContainers.horizontalFlow(Sizing.content(), Sizing.fixed(21));
        chip.verticalAlignment(VerticalAlignment.CENTER);
        chip.child(LumaUi.chip(label));
        return chip;
    }

    private int dialogWidth() {
        int availableWidth = Math.max(1, this.width - 20);
        if (availableWidth < MIN_DIALOG_WIDTH) {
            return availableWidth;
        }
        return Math.min(MAX_DIALOG_WIDTH, availableWidth);
    }
}
