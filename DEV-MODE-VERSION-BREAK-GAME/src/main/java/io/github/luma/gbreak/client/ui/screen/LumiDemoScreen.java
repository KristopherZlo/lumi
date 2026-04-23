package io.github.luma.gbreak.client.ui.screen;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public abstract class LumiDemoScreen extends BaseOwoScreen<FlowLayout> {

    protected LumiDemoScreen(Text title) {
        super(title);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
