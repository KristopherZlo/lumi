package io.github.lumi.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Fixed-size local arcade simulation; no world, network, or history access. */
final class LumiAstroInvadersGame {
    static final int WIDTH = 320, HEIGHT = 200, SHIP_Y = 180, MAX_SHOTS = 32;
    private final Random random = new Random(710);
    private final List<Invader> invaders = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private final List<Invader> invaderView = Collections.unmodifiableList(invaders);
    private final List<Shot> shotView = Collections.unmodifiableList(shots);
    private float shipX, direction, fireCooldown, enemyCooldown, immunity;
    private int score, lives, wave;
    private boolean paused;

    LumiAstroInvadersGame() { restart(); }

    void restart() {
        shipX = WIDTH / 2F;
        score = 0;
        lives = 3;
        wave = 0;
        immunity = fireCooldown = 0;
        paused = false;
        random.setSeed(710);
        nextWave();
    }

    private void nextWave() {
        wave++;
        direction = 1;
        enemyCooldown = 1;
        invaders.clear();
        shots.clear();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 8; column++) {
                invaders.add(new Invader(35 + column * 28, 28 + row * 18, row));
            }
        }
    }

    void advance(float seconds, boolean left, boolean right, boolean firing) {
        if (paused || gameOver()) return;
        float delta = Math.clamp(seconds, 0, .05F);
        if (delta == 0) return;
        shipX = Math.clamp(shipX + ((right ? 1 : 0) - (left ? 1 : 0)) * 130 * delta, 9, WIDTH - 9);
        immunity = Math.max(0, immunity - delta);
        fireCooldown -= delta;
        if (firing && fireCooldown <= 0 && shots.size() < MAX_SHOTS) {
            shots.add(new Shot(shipX, SHIP_Y - 5, false));
            fireCooldown = .22F;
        }
        float step = direction * (17 + Math.min(60, wave * 3) + (24 - invaders.size()) * 1.8F) * delta;
        boolean edge = invaders.stream().anyMatch(alien -> alien.x + step < 10 || alien.x + step > WIDTH - 10);
        for (Invader alien : invaders) {
            if (edge) alien.y += 7;
            else alien.x += step;
            if (alien.y >= SHIP_Y - 8) lives = 0;
        }
        if (edge) direction = -direction;
        enemyCooldown -= delta;
        if (enemyCooldown <= 0 && !invaders.isEmpty() && shots.size() < MAX_SHOTS) {
            var alien = invaders.get(random.nextInt(invaders.size()));
            shots.add(new Shot(alien.x, alien.y + 6, true));
            enemyCooldown = Math.max(.3F, .95F - wave * .04F);
        }
        for (var cursor = shots.iterator(); cursor.hasNext();) {
            Shot shot = cursor.next();
            shot.y += (shot.enemy ? 76 : -180) * delta;
            boolean hit = false;
            if (shot.enemy) {
                if (Math.abs(shot.x - shipX) < 7 && Math.abs(shot.y - SHIP_Y) < 6) {
                    if (immunity == 0) { lives = Math.max(0, lives - 1); immunity = 1.2F; }
                    hit = true;
                }
            } else {
                for (var targets = invaders.iterator(); targets.hasNext();) {
                    Invader alien = targets.next();
                    if (Math.abs(shot.x - alien.x) < 7 && Math.abs(shot.y - alien.y) < 6) {
                        score += (3 - alien.row) * 10;
                        targets.remove();
                        hit = true;
                        break;
                    }
                }
            }
            if (hit || shot.y < 0 || shot.y > HEIGHT) cursor.remove();
        }
        if (invaders.isEmpty() && !gameOver()) nextWave();
    }

    void togglePause() { paused = !paused; }
    boolean paused() { return paused; }
    boolean gameOver() { return lives == 0; }
    int score() { return score; }
    int lives() { return lives; }
    int wave() { return wave; }
    float shipX() { return shipX; }
    boolean immune() { return immunity > 0; }
    List<Invader> invaders() { return invaderView; }
    List<Shot> shots() { return shotView; }

    static final class Invader {
        private float x, y;
        private final int row;
        private Invader(float x, float y, int row) { this.x = x; this.y = y; this.row = row; }
        float x() { return x; }
        float y() { return y; }
        int row() { return row; }
    }

    static final class Shot {
        private final float x;
        private float y;
        private final boolean enemy;
        private Shot(float x, float y, boolean enemy) { this.x = x; this.y = y; this.enemy = enemy; }
        float x() { return x; }
        float y() { return y; }
        boolean enemy() { return enemy; }
    }
}
