package io.github.luma.ui;

import io.github.luma.ui.framework.component.ButtonComponent;
import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.framework.container.UIContainers;
import io.github.luma.ui.framework.core.Sizing;
import net.minecraft.network.chat.Component;

/**
 * A large, plain-language action row for primary builder workflows.
 */
public final class SimpleActionCard {

    private final Component step;
    private final Component title;
    private final Component help;
    private final ButtonComponent action;

    public SimpleActionCard(Component step, Component title, Component help, ButtonComponent action) {
        this.step = step;
        this.title = title;
        this.help = help;
        this.action = action;
    }

    public FlowLayout render(int screenWidth) {
        FlowLayout panel = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout row = screenWidth < 760
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);

        row.child(LumaUi.stepBadge(this.step));

        FlowLayout text = UIContainers.verticalFlow(screenWidth < 760 ? Sizing.fill(100) : Sizing.expand(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.value(this.title));
        text.child(LumaUi.caption(this.help));

        row.child(text);
        row.child(this.action);
        panel.child(row);
        return panel;
    }
}
