package io.github.luma.ui.screen;

import io.github.luma.ui.controller.CreateProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class CreateProjectScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final Minecraft client = Minecraft.getInstance();
    private final CreateProjectScreenController controller = new CreateProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private String status = "luma.status.create_ready";
    private String name = "";
    private String minX;
    private String minY;
    private String minZ;
    private String maxX;
    private String maxY;
    private String maxZ;

    public CreateProjectScreen(Screen parent) {
        super(Component.translatable("luma.screen.create_project.title"));
        this.parent = parent;
        BlockPos center = this.controller.suggestedCenter();
        this.minX = Integer.toString(center.getX() - 8);
        this.minY = Integer.toString(center.getY() - 8);
        this.minZ = Integer.toString(center.getZ() - 8);
        this.maxX = Integer.toString(center.getX() + 8);
        this.maxY = Integer.toString(center.getY() + 8);
        this.maxZ = Integer.toString(center.getZ() + 8);
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.create_project.title")).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.status)));

        root.child(UIComponents.label(Component.translatable("luma.create_project.name")));
        var nameBox = UIComponents.textBox(Sizing.fill(100), this.name);
        nameBox.onChanged().subscribe(value -> this.name = value);
        root.child(nameBox);

        root.child(buildCoordsRow("luma.create_project.min", this.minX, this.minY, this.minZ,
                value -> this.minX = value, value -> this.minY = value, value -> this.minZ = value));
        root.child(buildCoordsRow("luma.create_project.max", this.maxX, this.maxY, this.maxZ,
                value -> this.maxX = value, value -> this.maxY = value, value -> this.maxZ = value));

        root.child(UIComponents.button(Component.translatable("luma.action.create_project"), button -> {
            this.status = this.controller.createProject(
                    this.name,
                    new BlockPos(parse(this.minX), parse(this.minY), parse(this.minZ)),
                    new BlockPos(parse(this.maxX), parse(this.maxY), parse(this.maxZ))
            );
            if ("luma.status.project_created".equals(this.status)) {
                this.router.openProjectIgnoringRecovery(this.parent, this.name, this.status);
                return;
            }
            this.rebuild();
        }));
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout buildCoordsRow(
            String labelKey,
            String x,
            String y,
            String z,
            java.util.function.Consumer<String> onX,
            java.util.function.Consumer<String> onY,
            java.util.function.Consumer<String> onZ
    ) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.child(UIComponents.label(Component.translatable(labelKey)));

        var xBox = UIComponents.textBox(Sizing.fixed(60), x);
        xBox.onChanged().subscribe(onX::accept);
        row.child(xBox);

        var yBox = UIComponents.textBox(Sizing.fixed(60), y);
        yBox.onChanged().subscribe(onY::accept);
        row.child(yBox);

        var zBox = UIComponents.textBox(Sizing.fixed(60), z);
        zBox.onChanged().subscribe(onZ::accept);
        row.child(zBox);
        return row;
    }

    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }
}
