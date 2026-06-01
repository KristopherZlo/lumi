package io.github.luma.client.update;

import net.minecraft.client.Minecraft;

public final class MinecraftUpdateNoticeSink implements UpdateWorldJoinNotifier.UpdateNoticeSink {

    private final Minecraft client;
    private final UpdateChatMessageFactory messageFactory = new UpdateChatMessageFactory();

    public MinecraftUpdateNoticeSink(Minecraft client) {
        this.client = client;
    }

    @Override
    public boolean isReady() {
        return this.client != null && this.client.level != null && this.client.player != null;
    }

    @Override
    public void execute(Runnable task) {
        if (this.client != null && task != null) {
            this.client.execute(task);
        }
    }

    @Override
    public void sendUpdateNotice(UpdateRelease release) {
        if (this.isReady()) {
            this.client.player.displayClientMessage(this.messageFactory.create(release), false);
        }
    }
}
