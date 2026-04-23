package io.github.luma.gbreak.client.ui.screen;

import io.github.luma.gbreak.client.ui.LumiDemoUi;
import io.github.luma.gbreak.client.ui.controller.FakeLumiProjectScreenController;
import io.github.luma.gbreak.client.ui.state.FakeCommitEntry;
import io.github.luma.gbreak.client.ui.state.FakeLumiProjectViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class LumiDemoProjectScreen extends LumiDemoScreen {

    private final MinecraftClient client = MinecraftClient.getInstance();
    private final FakeLumiProjectScreenController controller = new FakeLumiProjectScreenController();
    private FakeLumiProjectViewState state;

    public LumiDemoProjectScreen() {
        super(Text.translatable("gbreakdev.ui.screen.project.title"));
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.client);

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumiDemoUi.screenFrame();
        root.child(frame);

        frame.child(this.headerSection());

        FlowLayout titleRow = LumiDemoUi.actionRow();
        titleRow.child(LumiDemoUi.value(Text.translatable("gbreakdev.ui.project.title", this.state.projectName())));
        titleRow.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.project.dimension", this.state.dimensionName())));
        titleRow.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.project.active_variant", this.state.activeVariantName())));
        frame.child(titleRow);
        frame.child(LumiDemoUi.statusBanner(Text.literal(this.state.statusMessage())));

        FlowLayout body = LumiDemoUi.screenBody();
        frame.child(LumiDemoUi.screenScroll(body));

        body.child(this.buildSection());
        body.child(this.historySection());
        body.child(this.moreSection());
        body.child(LumiDemoUi.bottomSpacer());
    }

    private FlowLayout headerSection() {
        FlowLayout header = LumiDemoUi.actionRow();
        header.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.back"), button -> this.closeScreen()));
        header.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.projects"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.projects"
        )));
        header.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.settings"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.settings"
        )));
        return header;
    }

    private FlowLayout buildSection() {
        FlowLayout section = LumiDemoUi.sectionCard(
                Text.translatable("gbreakdev.ui.section.build"),
                Text.translatable("gbreakdev.ui.section.build_help")
        );

        FlowLayout stats = LumiDemoUi.actionRow();
        stats.child(LumiDemoUi.statChip(
                Text.translatable("gbreakdev.ui.pending.added"),
                Text.literal(Integer.toString(this.state.pendingAdded()))
        ));
        stats.child(LumiDemoUi.statChip(
                Text.translatable("gbreakdev.ui.pending.removed"),
                Text.literal(Integer.toString(this.state.pendingRemoved()))
        ));
        stats.child(LumiDemoUi.statChip(
                Text.translatable("gbreakdev.ui.pending.changed"),
                Text.literal(Integer.toString(this.state.pendingChanged()))
        ));
        section.child(stats);

        FlowLayout actions = LumiDemoUi.actionRow();
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.save"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.save"
        )));
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.compare"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.compare"
        )));
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.restore_last_save"), button -> this.triggerFakeRestore(
                this.state.commits().getFirst().id()
        )));
        section.child(actions);
        return section;
    }

    private FlowLayout historySection() {
        FlowLayout section = LumiDemoUi.sectionCard(
                Text.translatable("gbreakdev.ui.section.history"),
                Text.translatable("gbreakdev.ui.section.history_help", this.state.activeVariantName())
        );

        FlowLayout variants = LumiDemoUi.actionRow();
        for (String variantName : this.state.variantNames()) {
            variants.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.project.variant_chip", variantName)));
        }
        section.child(variants);

        for (FakeCommitEntry commit : this.state.commits()) {
            section.child(this.commitCard(commit));
        }
        return section;
    }

    private FlowLayout commitCard(FakeCommitEntry commit) {
        FlowLayout card = LumiDemoUi.insetPanel(Sizing.fill(100), Sizing.content());

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(4);
        text.child(LumiDemoUi.value(Text.literal(commit.title())));
        text.child(LumiDemoUi.caption(Text.translatable(
                "gbreakdev.ui.commit.meta",
                commit.author(),
                commit.createdAt()
        )));
        text.child(LumiDemoUi.caption(Text.translatable(
                "gbreakdev.ui.commit.summary",
                commit.changedBlocks(),
                commit.changedChunks(),
                commit.variantName()
        )));

        FlowLayout meta = LumiDemoUi.actionRow();
        meta.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.commit.kind", commit.kindLabel())));
        meta.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.commit.id", commit.id())));
        if (commit.latest()) {
            meta.child(LumiDemoUi.chip(Text.translatable("gbreakdev.ui.commit.latest")));
        }
        text.child(meta);
        card.child(text);

        FlowLayout actions = LumiDemoUi.actionRow();
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.open"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.open"
        )));
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.compare"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.compare"
        )));
        ButtonComponent restoreButton = UIComponents.button(Text.translatable("gbreakdev.ui.action.restore"), button -> this.triggerFakeRestore(
                commit.id()
        ));
        actions.child(restoreButton);
        card.child(actions);
        return card;
    }

    private FlowLayout moreSection() {
        FlowLayout section = LumiDemoUi.sectionCard(
                Text.translatable("gbreakdev.ui.section.more"),
                Text.translatable("gbreakdev.ui.section.more_help")
        );
        section.child(LumiDemoUi.caption(Text.translatable("gbreakdev.ui.section.more_detail")));

        FlowLayout actions = LumiDemoUi.actionRow();
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.recovery"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.recovery"
        )));
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.variants"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.variants"
        )));
        actions.child(UIComponents.button(Text.translatable("gbreakdev.ui.action.settings"), button -> this.closePlaceholderAction(
                "gbreakdev.ui.action.settings"
        )));
        section.child(actions);
        return section;
    }

    private void closePlaceholderAction(String actionKey) {
        this.closeScreen();
        if (this.client.player != null) {
            this.client.player.sendMessage(Text.translatable(
                    "gbreakdev.ui.placeholder_action",
                    Text.translatable(actionKey)
            ), true);
        }
    }

    private void triggerFakeRestore(String targetCommitId) {
        this.closeScreen();
        ClientPlayNetworkHandler networkHandler = this.client.getNetworkHandler();
        if (networkHandler == null) {
            return;
        }

        networkHandler.sendChatCommand("gbreak fakerestore " + targetCommitId);
    }

    private void closeScreen() {
        this.client.setScreen(null);
    }
}
