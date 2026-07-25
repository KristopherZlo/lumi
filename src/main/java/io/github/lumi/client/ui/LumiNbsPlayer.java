package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Advances one bundled NBS song using vanilla note-block sounds. */
final class LumiNbsPlayer {
    private static final Identifier SONG = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "music/star_wars.nbs");
    private static final float VOLUME = 0.35F;
    private LumiNbsSong song;
    private boolean loadFailed;
    private int nextNote;
    private int previousTick = -1;
    private long previousMillis = -1L;

    void reset() {
        nextNote = 0;
        previousTick = -1;
        previousMillis = -1L;
    }

    void advance(Minecraft client, long elapsedMillis) {
        LumiNbsSong loaded = song(client);
        if (loaded == null) return;
        if (elapsedMillis < previousMillis) reset();
        int targetTick = (int) Math.floor(
                elapsedMillis * loaded.ticksPerSecond() / 1_000.0F);
        boolean resumedLate = previousMillis >= 0L
                && elapsedMillis - previousMillis > 500L;
        while (nextNote < loaded.notes().size()) {
            LumiNbsSong.Note note = loaded.notes().get(nextNote);
            if (note.tick() > targetTick) break;
            if (!resumedLate && note.tick() > previousTick) {
                play(client, note);
            }
            nextNote++;
        }
        previousTick = targetTick;
        previousMillis = elapsedMillis;
    }

    private LumiNbsSong song(Minecraft client) {
        if (song != null || loadFailed) return song;
        try {
            song = LumiNbsSong.read(
                    client.getResourceManager().open(SONG));
        } catch (IOException | RuntimeException failed) {
            loadFailed = true;
            LumiMod.LOGGER.warn(
                    "Cannot load Star Wars NBS easter egg", failed);
        }
        return song;
    }

    private static void play(Minecraft client, LumiNbsSong.Note note) {
        Holder<SoundEvent> sound = instrument(note.instrument());
        if (sound == null) return;
        client.getSoundManager().play(SimpleSoundInstance.forUI(
                sound.value(),
                foldedPitch(note.key(), note.pitchCents()),
                Math.max(0.0F, Math.min(1.0F, note.volume())) * VOLUME));
    }

    static float foldedPitch(int key, int pitchCents) {
        float pitch = (float) Math.pow(
                2.0, ((key - 45) * 100.0 + pitchCents) / 1_200.0);
        while (pitch < 0.5F) pitch *= 2.0F;
        while (pitch > 2.0F) pitch *= 0.5F;
        return pitch;
    }

    private static Holder<SoundEvent> instrument(int index) {
        return switch (index) {
            case 0 -> SoundEvents.NOTE_BLOCK_HARP;
            case 1 -> SoundEvents.NOTE_BLOCK_BASS;
            case 2 -> SoundEvents.NOTE_BLOCK_BASEDRUM;
            case 3 -> SoundEvents.NOTE_BLOCK_SNARE;
            case 4 -> SoundEvents.NOTE_BLOCK_HAT;
            case 5 -> SoundEvents.NOTE_BLOCK_GUITAR;
            case 6 -> SoundEvents.NOTE_BLOCK_FLUTE;
            case 7 -> SoundEvents.NOTE_BLOCK_BELL;
            case 8 -> SoundEvents.NOTE_BLOCK_CHIME;
            case 9 -> SoundEvents.NOTE_BLOCK_XYLOPHONE;
            case 10 -> SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE;
            case 11 -> SoundEvents.NOTE_BLOCK_COW_BELL;
            case 12 -> SoundEvents.NOTE_BLOCK_DIDGERIDOO;
            case 13 -> SoundEvents.NOTE_BLOCK_BIT;
            case 14 -> SoundEvents.NOTE_BLOCK_BANJO;
            case 15 -> SoundEvents.NOTE_BLOCK_PLING;
            default -> null;
        };
    }
}
