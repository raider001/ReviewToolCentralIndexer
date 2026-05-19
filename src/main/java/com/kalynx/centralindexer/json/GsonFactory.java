package com.kalynx.centralindexer.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Provides a shared, pre-configured {@link Gson} instance for the central indexer.
 *
 * <p>The instance handles:
 * <ul>
 *   <li>{@link Instant} — serialised as an ISO-8601 string (e.g. {@code "2026-05-19T10:00:00Z"}).</li>
 *   <li>{@code Map<String, String>} — handled natively by Gson without custom adapters.</li>
 *   <li>Null fields — serialised as JSON {@code null}, not omitted, so the full
 *       {@link com.kalynx.centralindexer.model.ReviewEvent} schema is always present in
 *       API responses.</li>
 * </ul>
 *
 * <p>The same instance is used for config deserialisation, {@code pg_notify} payload
 * serialisation, and HTTP response serialisation to ensure consistency.
 */
public final class GsonFactory {

    private static final Gson INSTANCE = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .serializeNulls()
            .create();

    private GsonFactory() {
    }

    /**
     * Returns the shared {@link Gson} instance.
     *
     * @return a thread-safe, pre-configured Gson instance
     */
    public static Gson getInstance() {
        return INSTANCE;
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {

        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}

