package dev.jdtlsagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.Collection;

final class Jsons {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Jsons() {
    }

    static JsonObject object() {
        return new JsonObject();
    }

    static JsonArray array() {
        return new JsonArray();
    }

    static void add(JsonObject object, String name, String value) {
        object.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    static void add(JsonObject object, String name, Number value) {
        object.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    static void add(JsonObject object, String name, Boolean value) {
        object.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    static String string(JsonObject object, String name, String defaultValue) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        return value.getAsString();
    }

    static int integer(JsonObject object, String name, int defaultValue) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        return value.getAsInt();
    }

    static boolean bool(JsonObject object, String name, boolean defaultValue) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        return value.getAsBoolean();
    }

    static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    static String path(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
