package io.github.lumi.observation;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Repeatable creative flight through normal client input and chunk loading. */
final class OrdinaryPlayRoute {
    private final Set<Long> chunks = new HashSet<>();
    private Vec3 previous;
    private double altitude;
    private int ticks;
    private volatile double distance;
    private volatile int chunkCount;

    void tick(Minecraft client) {
        if (client.screen != null || client.player == null) return;
        var player = client.player;
        if (previous == null) {
            if (!player.getAbilities().mayfly) throw new IllegalStateException("Play route requires creative flight");
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            altitude = Math.min(client.level.getMaxY() - 2, player.getY() + 80);
            previous = player.position();
        }
        distance += player.position().distanceTo(previous);
        previous = player.position();
        if (chunks.size() < 256) chunks.add(player.chunkPosition().toLong());
        chunkCount = chunks.size();
        client.options.keyUp.setDown(true);
        client.options.keyJump.setDown(player.getY() < altitude);
        player.setYRot(90 * (ticks++ / 1800 % 4));
    }

    void verify(int seconds) {
        if (distance < Math.min(128, seconds / 4.0) || chunkCount < Math.min(8, 1 + seconds / 120)) {
            throw new IllegalStateException("Play route did not move: " + describe());
        }
    }

    String describe() { return "distance=" + distance + ";chunks=" + chunkCount; }
}
