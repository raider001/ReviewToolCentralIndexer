package com.kalynx.centralindexer.json;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link GsonFactory}.
 */
class GsonFactoryTest {

    private final Gson gson = GsonFactory.getInstance();

    @Test
    void instantRoundTrip() {
        Instant original = Instant.parse("2026-05-19T10:00:00Z");
        String json = gson.toJson(original);
        Instant restored = gson.fromJson(json, Instant.class);
        assertEquals(original, restored);
    }

    @Test
    void mapStringStringRoundTrip() {
        Map<String, String> original = Map.of("key1", "value1", "key2", "value2");
        String json = gson.toJson(original);
        @SuppressWarnings("unchecked")
        Map<String, String> restored = gson.fromJson(json, Map.class);
        assertEquals(original.get("key1"), restored.get("key1"));
        assertEquals(original.get("key2"), restored.get("key2"));
    }

    @Test
    void nullFieldsSerializedAsJsonNull() {
        String json = gson.toJson(new NullFieldHolder(null));
        assertNotNull(json);
        assertEquals("{\"value\":null}", json);
    }

    @Test
    void instantSerializedAsIso8601String() {
        Instant i = Instant.parse("2026-01-01T00:00:00Z");
        String json = gson.toJson(i);
        assertEquals("\"2026-01-01T00:00:00Z\"", json);
    }

    private record NullFieldHolder(String value) {
    }
}

