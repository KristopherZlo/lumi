package io.github.lumi.client.ui;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable note-block song parsed from one bounded NBS resource. */
record LumiNbsSong(float ticksPerSecond, List<Note> notes) {
    private static final int MAX_LAYERS = 1_024;
    private static final int MAX_NOTES = 200_000;
    private static final int MAX_STRING_BYTES = 1_048_576;

    LumiNbsSong {
        if (!Float.isFinite(ticksPerSecond) || ticksPerSecond <= 0.0F) {
            throw new IllegalArgumentException("NBS tempo must be positive");
        }
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    }

    static LumiNbsSong read(InputStream source) throws IOException {
        try (DataInputStream input = new DataInputStream(
                Objects.requireNonNull(source, "source"))) {
            int legacyLength = unsignedShort(input);
            int version = 0;
            if (legacyLength == 0) {
                version = input.readUnsignedByte();
                input.readUnsignedByte();
                unsignedShort(input);
            }
            if (version > 5) {
                throw new IOException("Unsupported NBS version: " + version);
            }
            int layerCount = unsignedShort(input);
            if (layerCount < 1 || layerCount > MAX_LAYERS) {
                throw new IOException("Invalid NBS layer count: " + layerCount);
            }
            skipString(input);
            skipString(input);
            skipString(input);
            skipString(input);
            float tempo = unsignedShort(input) / 100.0F;
            input.readUnsignedByte();
            input.readUnsignedByte();
            input.readUnsignedByte();
            for (int index = 0; index < 5; index++) littleInt(input);
            skipString(input);
            if (version >= 4) {
                input.readUnsignedByte();
                input.readUnsignedByte();
                unsignedShort(input);
            }

            List<RawNote> rawNotes = readNotes(input, version, layerCount);
            int[] layerVolumes = readLayerVolumes(input, version, layerCount);
            List<Note> notes = new ArrayList<>(rawNotes.size());
            for (RawNote raw : rawNotes) {
                float volume = raw.velocity() * layerVolumes[raw.layer()]
                        / 10_000.0F;
                notes.add(new Note(
                        raw.tick(), raw.instrument(), raw.key(),
                        raw.pitchCents(), volume));
            }
            return new LumiNbsSong(tempo, notes);
        }
    }

    private static List<RawNote> readNotes(
            DataInputStream input, int version, int layerCount)
            throws IOException {
        List<RawNote> notes = new ArrayList<>();
        int tick = -1;
        int tickJump;
        while ((tickJump = unsignedShort(input)) != 0) {
            tick = Math.addExact(tick, tickJump);
            int layer = -1;
            int layerJump;
            while ((layerJump = unsignedShort(input)) != 0) {
                layer = Math.addExact(layer, layerJump);
                if (layer >= layerCount) {
                    throw new IOException("NBS note references an unknown layer");
                }
                int instrument = input.readUnsignedByte();
                int key = input.readUnsignedByte();
                int velocity = version >= 4
                        ? input.readUnsignedByte() : 100;
                if (version >= 4) {
                    input.readUnsignedByte();
                }
                int pitch = version >= 4 ? signedShort(input) : 0;
                notes.add(new RawNote(
                        tick, layer, instrument, key, pitch, velocity));
                if (notes.size() > MAX_NOTES) {
                    throw new IOException("NBS note limit exceeded");
                }
            }
        }
        return notes;
    }

    private static int[] readLayerVolumes(
            DataInputStream input, int version, int layerCount)
            throws IOException {
        int[] volumes = new int[layerCount];
        for (int layer = 0; layer < layerCount; layer++) {
            skipString(input);
            if (version >= 4) input.readUnsignedByte();
            volumes[layer] = input.readUnsignedByte();
            if (version >= 2) input.readUnsignedByte();
        }
        return volumes;
    }

    private static void skipString(DataInputStream input) throws IOException {
        int length = littleInt(input);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid NBS string length: " + length);
        }
        input.skipNBytes(length);
    }

    private static int unsignedShort(DataInputStream input) throws IOException {
        return input.readUnsignedByte() | input.readUnsignedByte() << 8;
    }

    private static short signedShort(DataInputStream input) throws IOException {
        return (short) unsignedShort(input);
    }

    private static int littleInt(DataInputStream input) throws IOException {
        return input.readUnsignedByte()
                | input.readUnsignedByte() << 8
                | input.readUnsignedByte() << 16
                | input.readUnsignedByte() << 24;
    }

    record Note(
            int tick, int instrument, int key, int pitchCents, float volume) { }

    private record RawNote(
            int tick,
            int layer,
            int instrument,
            int key,
            int pitchCents,
            int velocity) { }
}
