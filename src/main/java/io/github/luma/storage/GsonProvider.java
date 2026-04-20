package io.github.luma.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import java.time.Instant;

public final class GsonProvider {

    private static final Gson GSON = create(true);
    private static final Gson COMPACT_GSON = create(false);

    private GsonProvider() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static Gson compactGson() {
        return COMPACT_GSON;
    }

    private static Gson create(boolean prettyPrinting) {
        GsonBuilder builder = new GsonBuilder()
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (src, typeOfSrc, context) -> new JsonPrimitive(src.toString()))
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, typeOfT, context) -> Instant.parse(json.getAsString()));

        if (prettyPrinting) {
            builder.setPrettyPrinting();
        }

        return builder.create();
    }
}
