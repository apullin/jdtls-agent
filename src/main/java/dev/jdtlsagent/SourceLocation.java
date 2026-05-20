package dev.jdtlsagent;

import com.google.gson.JsonObject;

import java.nio.file.Path;

record SourceLocation(Path file, int line, int column, int offset) {
    JsonObject toJson() {
        JsonObject object = Jsons.object();
        Jsons.add(object, "file", Jsons.path(file));
        Jsons.add(object, "line", line);
        Jsons.add(object, "column", column);
        Jsons.add(object, "offset", offset);
        return object;
    }
}
