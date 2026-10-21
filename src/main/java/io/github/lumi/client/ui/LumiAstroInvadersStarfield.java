package io.github.lumi.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Fixed pixel stars with quiet contrast and one random position per background cell. */
final class LumiAstroInvadersStarfield {
    private final List<Star> stars;

    LumiAstroInvadersStarfield(Random random) {
        var cells = new ArrayList<Integer>();
        for (int cell = 0; cell < 40; cell++) cells.add(cell);
        Collections.shuffle(cells, random);
        var generated = new ArrayList<Star>();
        for (int index = 0; index < cells.size(); index++) {
            int cell = cells.get(index);
            int x = 8 + cell % 8 * 38 + random.nextInt(3, 35);
            int y = 24 + cell / 8 * 32 + random.nextInt(3, 29);
            int color = index < 2 ? 0xff8996ad : index < 10 ? 0xff566174 : 0xff353c4a;
            generated.add(new Star(x, y, color, index < 2));
        }
        stars = List.copyOf(generated);
    }

    List<Star> stars() { return stars; }

    record Star(int x, int y, int color, boolean sparkle) { }
}
