package dev.jdtlsagent;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

record SourceSymbol(
        String kind,
        String name,
        String owner,
        String qualifiedName,
        Path file,
        int line,
        int column,
        int nameOffset,
        int bodyStartOffset,
        int bodyEndOffset,
        List<String> parameterTypes,
        List<String> modifiers
) {
    SourceLocation location() {
        return new SourceLocation(file, line, column, nameOffset);
    }

    JsonObject toJson() {
        JsonObject object = Jsons.object();
        Jsons.add(object, "kind", kind);
        Jsons.add(object, "name", name);
        Jsons.add(object, "owner", owner);
        Jsons.add(object, "qualifiedName", qualifiedName);
        Jsons.add(object, "file", Jsons.path(file));
        Jsons.add(object, "line", line);
        Jsons.add(object, "column", column);
        object.add("parameterTypes", Jsons.strings(parameterTypes));
        object.add("modifiers", Jsons.strings(modifiers));
        return object;
    }

    String displayName() {
        if (parameterTypes.isEmpty() || !"method".equals(kind)) {
            return qualifiedName;
        }
        return qualifiedName + "(" + String.join(",", parameterTypes) + ")";
    }
}
