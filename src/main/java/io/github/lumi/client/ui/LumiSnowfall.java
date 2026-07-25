package io.github.lumi.client.ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;

/** Bounded snow particles that finish falling after their Lumi card loses hover. */
final class LumiSnowfall {
    private static final int MAX_FLAKES = 96;
    private static final float FLAKES_PER_SECOND = 38.0F;
    private final Random random = new Random(0x4c554d49L);
    private final List<Flake> flakes = new ArrayList<>();
    private long lastMillis = Long.MIN_VALUE;
    private float spawnBudget;

    void render(
            GuiGraphics graphics, boolean emitting,
            int width, int height, long nowMillis) {
        advance(emitting, width, height, nowMillis);
        for (Flake flake : flakes) {
            int x = Math.round(flake.x
                    + (float) Math.sin(flake.phase + flake.y * 0.035F)
                    * flake.drift);
            int y = Math.round(flake.y);
            graphics.fill(
                    x, y, x + flake.size, y + flake.size,
                    flake.size == 1 ? 0xccffffff : 0xeeffffff);
        }
    }

    int advance(boolean emitting, int width, int height, long nowMillis) {
        if (lastMillis == Long.MIN_VALUE) {
            lastMillis = nowMillis;
            return flakes.size();
        }
        float seconds = Math.min(0.1F,
                Math.max(0L, nowMillis - lastMillis) / 1_000.0F);
        lastMillis = nowMillis;
        if (emitting) {
            spawnBudget += seconds * FLAKES_PER_SECOND;
            while (spawnBudget >= 1.0F && flakes.size() < MAX_FLAKES) {
                flakes.add(new Flake(
                        random.nextFloat() * Math.max(1, width),
                        -2.0F,
                        18.0F + random.nextFloat() * 28.0F,
                        1.0F + random.nextFloat() * 3.0F,
                        random.nextFloat() * (float) Math.PI * 2.0F,
                        random.nextBoolean() ? 1 : 2));
                spawnBudget -= 1.0F;
            }
        } else {
            spawnBudget = 0.0F;
        }
        for (Iterator<Flake> cursor = flakes.iterator(); cursor.hasNext();) {
            Flake flake = cursor.next();
            flake.y += flake.speed * seconds;
            if (flake.y > height + 2) cursor.remove();
        }
        return flakes.size();
    }

    private static final class Flake {
        private final float x;
        private float y;
        private final float speed;
        private final float drift;
        private final float phase;
        private final int size;

        private Flake(
                float x, float y, float speed,
                float drift, float phase, int size) {
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.drift = drift;
            this.phase = phase;
            this.size = size;
        }
    }
}
