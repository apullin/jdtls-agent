package dev.jdtlsagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class QueryService {
    private final AgentPaths paths;
    private final AgentOptions daemonOptions;
    private final SourceIndex sourceIndex;
    private final JdtlsClient jdtls;
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong totalLatencyNanos = new AtomicLong();

    QueryService(AgentPaths paths, AgentOptions daemonOptions, SourceIndex sourceIndex, JdtlsClient jdtls) {
        this.paths = paths;
        this.daemonOptions = daemonOptions;
        this.sourceIndex = sourceIndex;
        this.jdtls = jdtls;
    }

    JsonObject execute(JsonObject request) {
        long start = System.nanoTime();
        String command = Jsons.string(request, "command", "");
        try {
            JsonObject result = switch (command) {
                case "status" -> status();
                case "symbol" -> symbol(request);
                case "definition" -> definition(request);
                case "references" -> references(request);
                case "callers" -> callers(request);
                case "callees" -> callees(request);
                case "diagnostics" -> diagnostics(request);
                case "field-writes" -> fieldWrites(request);
                case "api-surface" -> apiSurface(request);
                case "batch" -> batch(request);
                default -> error("unknown-command", "Unknown command: " + command);
            };
            Jsons.add(result, "timingMs", (System.nanoTime() - start) / 1_000_000L);
            return result;
        } catch (Exception e) {
            JsonObject error = error("command-failed", e.getMessage() == null ? e.toString() : e.getMessage());
            Jsons.add(error, "timingMs", (System.nanoTime() - start) / 1_000_000L);
            return error;
        } finally {
            queryCount.incrementAndGet();
            totalLatencyNanos.addAndGet(System.nanoTime() - start);
        }
    }

    private JsonObject status() {
        JsonObject object = ok("status");
        Jsons.add(object, "daemonUp", true);
        Jsons.add(object, "project", Jsons.path(paths.project));
        Jsons.add(object, "sourceFiles", sourceIndex.sourceFileCount());
        Jsons.add(object, "queryCount", queryCount.get());
        long count = Math.max(1, queryCount.get());
        Jsons.add(object, "averageLatencyMs", totalLatencyNanos.get() / count / 1_000_000L);
        object.add("jdtls", jdtls.statusJson());
        return object;
    }

    private JsonObject symbol(JsonObject request) throws Exception {
        String query = firstArg(request);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        boolean includeTests = optionBool(request, "includeTests", daemonOptions.includeTests);
        List<SourceSymbol> local = sourceIndex.search(query, includeTests, limit);
        JsonObject object = ok("symbol");
        Jsons.add(object, "query", query);
        object.add("results", sourceIndex.symbolsToJson(local));
        try {
            JsonObject params = Jsons.object();
            Jsons.add(params, "query", query);
            JsonElement lsp = jdtls.request("workspace/symbol", params, Duration.ofSeconds(10));
            object.add("workspaceSymbolResultCount", new com.google.gson.JsonPrimitive(lsp.isJsonArray() ? lsp.getAsJsonArray().size() : 0));
            if (optionBool(request, "full", false)) {
                object.add("workspaceSymbols", lsp);
            }
        } catch (Exception e) {
            Jsons.add(object, "workspaceSymbolWarning", e.getMessage());
        }
        return object;
    }

    private JsonObject definition(JsonObject request) {
        String query = firstArg(request);
        SourceIndex.ResolveResult resolved = sourceIndex.resolve(query, optionBool(request, "includeTests", true));
        if (!resolved.ok()) {
            JsonObject error = resolved.toErrorJson();
            Jsons.add(error, "command", "definition");
            return error;
        }
        JsonObject object = ok("definition");
        Jsons.add(object, "query", query);
        object.add("resolvedSymbol", resolved.symbol().toJson());
        object.add("location", resolved.symbol().location().toJson());
        return object;
    }

    private JsonObject references(JsonObject request) throws Exception {
        String query = firstArg(request);
        boolean includeTests = optionBool(request, "includeTests", true);
        boolean includeDeclaration = optionBool(request, "includeDeclaration", false);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        SourceIndex.ResolveResult resolved = sourceIndex.resolve(query, includeTests);
        if (!resolved.ok()) {
            JsonObject error = resolved.toErrorJson();
            Jsons.add(error, "command", "references");
            return error;
        }
        SourceSymbol symbol = resolved.symbol();
        JsonArray results = referenceResultsForSymbol(symbol, includeTests, includeDeclaration, limit, "reference");
        JsonObject object = ok("references");
        Jsons.add(object, "query", query);
        object.add("resolvedSymbol", symbol.toJson());
        object.add("results", results);
        return object;
    }

    private JsonObject callers(JsonObject request) throws Exception {
        String query = firstArg(request);
        boolean includeTests = optionBool(request, "includeTests", true);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        SourceIndex.ResolveResult resolved = sourceIndex.resolve(query, includeTests);
        if (!resolved.ok()) {
            JsonObject error = resolved.toErrorJson();
            Jsons.add(error, "command", "callers");
            return error;
        }
        SourceSymbol symbol = resolved.symbol();
        jdtls.didOpen(symbol.file());
        JsonArray callHierarchy = prepareCallHierarchy(symbol);
        JsonArray results = Jsons.array();
        String fallback = null;
        if (callHierarchy.size() > 0) {
            JsonObject params = Jsons.object();
            params.add("item", callHierarchy.get(0));
            JsonElement incoming = jdtls.request("callHierarchy/incomingCalls", params, Duration.ofSeconds(30));
            results = incomingCallsToJson(incoming, includeTests, limit);
        }
        if (results.size() == 0) {
            fallback = "references-grouped-by-enclosing-method";
            JsonObject refRequest = request.deepCopy();
            setOption(refRequest, "includeDeclaration", false);
            JsonObject refs = references(refRequest);
            results = groupReferencesByCaller(refs.getAsJsonArray("results"), limit);
        }
        JsonObject object = ok("callers");
        Jsons.add(object, "query", query);
        object.add("resolvedSymbol", symbol.toJson());
        object.add("results", results);
        if (fallback != null) {
            Jsons.add(object, "fallback", fallback);
        }
        return object;
    }

    private JsonObject callees(JsonObject request) throws Exception {
        String query = firstArg(request);
        boolean includeTests = optionBool(request, "includeTests", true);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        SourceIndex.ResolveResult resolved = sourceIndex.resolve(query, includeTests);
        if (!resolved.ok()) {
            JsonObject error = resolved.toErrorJson();
            Jsons.add(error, "command", "callees");
            return error;
        }
        SourceSymbol symbol = resolved.symbol();
        jdtls.didOpen(symbol.file());
        JsonArray callHierarchy = prepareCallHierarchy(symbol);
        JsonArray results = Jsons.array();
        if (callHierarchy.size() > 0) {
            JsonObject params = Jsons.object();
            params.add("item", callHierarchy.get(0));
            JsonElement outgoing = jdtls.request("callHierarchy/outgoingCalls", params, Duration.ofSeconds(30));
            results = outgoingCallsToJson(outgoing, symbol.file().toUri().toString(), includeTests, limit);
        }
        JsonObject object = ok("callees");
        Jsons.add(object, "query", query);
        object.add("resolvedSymbol", symbol.toJson());
        object.add("results", results);
        if (callHierarchy.size() == 0) {
            Jsons.add(object, "warning", "jdtls returned no call hierarchy item for the resolved symbol");
        }
        return object;
    }

    private JsonObject fieldWrites(JsonObject request) throws Exception {
        String query = firstArg(request);
        boolean includeTests = optionBool(request, "includeTests", true);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        SourceIndex.ResolveResult resolved = sourceIndex.resolve(query, includeTests);
        if (!resolved.ok()) {
            JsonObject error = resolved.toErrorJson();
            Jsons.add(error, "command", "field-writes");
            return error;
        }
        SourceSymbol symbol = resolved.symbol();
        if (!"field".equals(symbol.kind())) {
            return error("not-a-field", "field-writes requires a field symbol, got " + symbol.kind() + ": " + symbol.displayName());
        }
        JsonArray references = referenceResultsForSymbol(symbol, includeTests, false, Integer.MAX_VALUE, "field-write");
        JsonArray writes = Jsons.array();
        for (JsonElement element : references) {
            if (writes.size() >= limit) {
                break;
            }
            JsonObject reference = element.getAsJsonObject();
            Path file = Path.of(Jsons.string(reference, "file", ""));
            int line = Jsons.integer(reference, "line", 0);
            int column = Jsons.integer(reference, "column", 0);
            int offset = sourceIndex.offset(file, line, column);
            if (sourceIndex.isWriteReference(file, offset)) {
                writes.add(reference);
            }
        }
        JsonObject object = ok("field-writes");
        Jsons.add(object, "query", query);
        object.add("resolvedSymbol", symbol.toJson());
        object.add("results", writes);
        return object;
    }

    private JsonObject apiSurface(JsonObject request) throws Exception {
        String query = firstArg(request);
        boolean includeTests = optionBool(request, "includeTests", true);
        int limit = optionInt(request, "limit", daemonOptions.limit);
        String scope = query;
        SourceIndex.ResolveResult scopeResolve = sourceIndex.resolve(query, includeTests);
        if (scopeResolve.ok()) {
            if (!"class".equals(scopeResolve.symbol().kind())) {
                return error("not-a-class-or-package", "api-surface requires a class or package scope, got " + scopeResolve.symbol().kind() + ": " + scopeResolve.symbol().displayName());
            }
            scope = scopeResolve.symbol().qualifiedName();
        } else if ("ambiguous-symbol".equals(scopeResolve.error())) {
            List<SourceSymbol> classCandidates = scopeResolve.candidates().stream()
                    .filter(candidate -> "class".equals(candidate.kind()))
                    .toList();
            if (classCandidates.size() == 1) {
                scope = classCandidates.get(0).qualifiedName();
            } else {
                JsonObject error = scopeResolve.toErrorJson();
                Jsons.add(error, "command", "api-surface");
                return error;
            }
        }
        List<SourceSymbol> methods = sourceIndex.apiSurface(scope, includeTests, limit);
        JsonArray results = Jsons.array();
        for (SourceSymbol method : methods) {
            JsonObject item = method.toJson();
            JsonArray references = referenceResultsForSymbol(method, includeTests, false, daemonOptions.limit, "reference");
            JsonArray callers = groupReferencesByCaller(references, daemonOptions.limit);
            item.add("callers", callers);
            Jsons.add(item, "callerCount", callers.size());
            results.add(item);
        }
        JsonObject object = ok("api-surface");
        Jsons.add(object, "query", query);
        Jsons.add(object, "resolvedScope", scope);
        object.add("results", results);
        return object;
    }

    private JsonObject diagnostics(JsonObject request) throws Exception {
        String fileOption = optionString(request, "file", null);
        Path file = null;
        if (fileOption != null && !fileOption.isBlank()) {
            file = paths.project.resolve(fileOption).toAbsolutePath().normalize();
            jdtls.didOpen(file);
            Thread.sleep(750L);
        }
        boolean errorsOnly = optionBool(request, "errorsOnly", false);
        boolean diagnosticsPublished = jdtls.diagnosticsPublished(file);
        JsonArray raw = jdtls.diagnostics(file);
        JsonArray results = Jsons.array();
        for (JsonElement element : raw) {
            JsonObject diagnostic = element.getAsJsonObject();
            int severity = diagnostic.has("severity") ? diagnostic.get("severity").getAsInt() : 0;
            if (errorsOnly && severity != 1) {
                continue;
            }
            JsonObject object = Jsons.object();
            String uri = Jsons.string(diagnostic, "uri", file == null ? null : file.toUri().toString());
            Path diagnosticFile = uri == null ? file : Path.of(URI.create(uri));
            JsonObject range = diagnostic.getAsJsonObject("range");
            JsonObject start = range == null ? Jsons.object() : range.getAsJsonObject("start");
            int line = start == null ? 1 : start.get("line").getAsInt() + 1;
            int column = start == null ? 1 : start.get("character").getAsInt() + 1;
            Jsons.add(object, "file", diagnosticFile == null ? null : Jsons.path(diagnosticFile));
            Jsons.add(object, "line", line);
            Jsons.add(object, "column", column);
            Jsons.add(object, "severity", severityName(severity));
            Jsons.add(object, "message", Jsons.string(diagnostic, "message", ""));
            Jsons.add(object, "source", Jsons.string(diagnostic, "source", ""));
            if (diagnosticFile != null) {
                Jsons.add(object, "preview", sourceIndex.preview(diagnosticFile, line));
            }
            results.add(object);
        }
        JsonObject object = ok("diagnostics");
        object.add("results", results);
        Jsons.add(object, "diagnosticsPublished", diagnosticsPublished);
        Jsons.add(object, "diagnosticsAvailable", diagnosticsPublished);
        if (!diagnosticsPublished) {
            Jsons.add(object, "message", "JDTLS has not published diagnostics for this scope yet; use --file or retry after indexing settles.");
        } else if (results.size() == 0) {
            Jsons.add(object, "message", errorsOnly ? "No error diagnostics are currently published." : "No diagnostics are currently published.");
        }
        return object;
    }

    private JsonObject batch(JsonObject request) {
        JsonArray commands = request.getAsJsonArray("commands");
        JsonArray results = Jsons.array();
        if (commands != null) {
            for (JsonElement element : commands) {
                results.add(execute(element.getAsJsonObject()));
            }
        }
        JsonObject object = ok("batch");
        object.add("results", results);
        return object;
    }

    private JsonArray prepareCallHierarchy(SourceSymbol symbol) throws Exception {
        JsonElement response = jdtls.request("textDocument/prepareCallHierarchy", textDocumentPosition(symbol), Duration.ofSeconds(20));
        return response != null && response.isJsonArray() ? response.getAsJsonArray() : Jsons.array();
    }

    private JsonObject textDocumentPosition(SourceSymbol symbol) {
        JsonObject params = Jsons.object();
        JsonObject textDocument = Jsons.object();
        Jsons.add(textDocument, "uri", symbol.file().toUri().toString());
        params.add("textDocument", textDocument);
        JsonObject position = Jsons.object();
        Jsons.add(position, "line", Math.max(0, symbol.line() - 1));
        Jsons.add(position, "character", Math.max(0, symbol.column() - 1));
        params.add("position", position);
        return params;
    }

    private JsonArray referenceResultsForSymbol(SourceSymbol symbol, boolean includeTests, boolean includeDeclaration, int limit, String kind) throws Exception {
        jdtls.didOpen(symbol.file());
        JsonObject params = textDocumentPosition(symbol);
        JsonObject context = Jsons.object();
        Jsons.add(context, "includeDeclaration", includeDeclaration);
        params.add("context", context);
        JsonElement response = jdtls.request("textDocument/references", params, Duration.ofSeconds(30));
        return locationsToJson(response, includeTests, limit, kind);
    }

    private JsonArray locationsToJson(JsonElement response, boolean includeTests, int limit, String kind) {
        JsonArray array = Jsons.array();
        if (response == null || !response.isJsonArray()) {
            return array;
        }
        for (JsonElement element : response.getAsJsonArray()) {
            if (array.size() >= limit) {
                break;
            }
            JsonObject location = element.getAsJsonObject();
            String uri;
            JsonObject range;
            if (location.has("uri")) {
                uri = location.get("uri").getAsString();
                range = location.getAsJsonObject("range");
            } else {
                uri = Jsons.string(location, "targetUri", "");
                range = location.has("targetSelectionRange") ? location.getAsJsonObject("targetSelectionRange") : location.getAsJsonObject("targetRange");
            }
            JsonObject converted = rangeJson(uri, range, kind);
            Path path = Path.of(URI.create(uri));
            if (!includeTests && sourceIndex.isTestFile(path)) {
                continue;
            }
            int line = converted.get("line").getAsInt();
            Jsons.add(converted, "preview", sourceIndex.preview(path, line));
            int offset = sourceIndex.offset(path, line, converted.get("column").getAsInt());
            sourceIndex.enclosingMethod(path, offset).ifPresent(method -> Jsons.add(converted, "enclosingSymbol", method.displayName()));
            array.add(converted);
        }
        return array;
    }

    private JsonArray incomingCallsToJson(JsonElement response, boolean includeTests, int limit) {
        JsonArray array = Jsons.array();
        if (response == null || !response.isJsonArray()) {
            return array;
        }
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (JsonElement element : response.getAsJsonArray()) {
            JsonObject call = element.getAsJsonObject();
            JsonObject from = call.getAsJsonObject("from");
            String uri = Jsons.string(from, "uri", "");
            Path file = Path.of(URI.create(uri));
            if (!includeTests && sourceIndex.isTestFile(file)) {
                continue;
            }
            JsonObject object = callItemJson(from, "caller");
            JsonObject group = groups.computeIfAbsent(incomingCallItemKey(object), ignored -> {
                object.add("callSites", Jsons.array());
                return object;
            });
            JsonArray sites = rangesJson(uri, call.getAsJsonArray("fromRanges"), "call-site");
            JsonArray callSites = group.getAsJsonArray("callSites");
            for (JsonElement site : sites) {
                JsonObject siteObject = site.getAsJsonObject();
                if (!hasCallSite(callSites, siteObject)) {
                    callSites.add(siteObject);
                }
            }
            if (groups.size() >= limit) {
                break;
            }
        }
        for (JsonObject value : groups.values()) {
            array.add(value);
        }
        return array;
    }

    private JsonArray outgoingCallsToJson(JsonElement response, String callSiteUri, boolean includeTests, int limit) {
        JsonArray array = Jsons.array();
        if (response == null || !response.isJsonArray()) {
            return array;
        }
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (JsonElement element : response.getAsJsonArray()) {
            JsonObject call = element.getAsJsonObject();
            JsonObject to = call.getAsJsonObject("to");
            String uri = Jsons.string(to, "uri", "");
            Path file = Path.of(URI.create(uri));
            if (!includeTests && sourceIndex.isTestFile(file)) {
                continue;
            }
            JsonObject object = callItemJson(to, "callee");
            JsonObject group = groups.computeIfAbsent(callItemKey(object), ignored -> {
                object.add("callSites", Jsons.array());
                return object;
            });
            JsonArray sites = rangesJson(callSiteUri, call.getAsJsonArray("fromRanges"), "call-site");
            JsonArray callSites = group.getAsJsonArray("callSites");
            for (JsonElement site : sites) {
                JsonObject siteObject = site.getAsJsonObject();
                if (!hasCallSite(callSites, siteObject)) {
                    callSites.add(siteObject);
                }
            }
            if (groups.size() >= limit) {
                break;
            }
        }
        for (JsonObject value : groups.values()) {
            array.add(value);
        }
        return array;
    }

    private static String callItemKey(JsonObject object) {
        return Jsons.string(object, "file", "") + ":" + Jsons.integer(object, "line", 0) + ":"
                + Jsons.integer(object, "column", 0) + ":" + Jsons.string(object, "name", "") + ":"
                + Jsons.string(object, "detail", "");
    }

    private static String incomingCallItemKey(JsonObject object) {
        return Jsons.string(object, "file", "") + ":" + Jsons.string(object, "name", "") + ":"
                + Jsons.string(object, "detail", "");
    }

    private static boolean hasCallSite(JsonArray callSites, JsonObject candidate) {
        String candidateKey = callSiteKey(candidate);
        for (JsonElement element : callSites) {
            if (callSiteKey(element.getAsJsonObject()).equals(candidateKey)) {
                return true;
            }
        }
        return false;
    }

    private static String callSiteKey(JsonObject site) {
        return Jsons.string(site, "file", "") + ":" + Jsons.integer(site, "line", 0) + ":"
                + Jsons.integer(site, "column", 0) + ":" + Jsons.string(site, "preview", "");
    }

    private JsonObject callItemJson(JsonObject item, String relation) {
        String uri = Jsons.string(item, "uri", "");
        JsonObject selection = item.has("selectionRange") ? item.getAsJsonObject("selectionRange") : item.getAsJsonObject("range");
        JsonObject object = rangeJson(uri, selection, relation);
        Jsons.add(object, "name", Jsons.string(item, "name", ""));
        Jsons.add(object, "detail", Jsons.string(item, "detail", ""));
        Jsons.add(object, "symbolKind", item.has("kind") ? item.get("kind").getAsInt() : 0);
        return object;
    }

    private JsonArray rangesJson(String uri, JsonArray ranges, String kind) {
        JsonArray array = Jsons.array();
        if (ranges == null) {
            return array;
        }
        for (JsonElement rangeElement : ranges) {
            JsonObject object = rangeJson(uri, rangeElement.getAsJsonObject(), kind);
            Path path = Path.of(URI.create(uri));
            int line = object.get("line").getAsInt();
            Jsons.add(object, "preview", sourceIndex.preview(path, line));
            array.add(object);
        }
        return array;
    }

    private JsonObject rangeJson(String uri, JsonObject range, String kind) {
        Path path = Path.of(URI.create(uri));
        JsonObject start = range.getAsJsonObject("start");
        int line = start.get("line").getAsInt() + 1;
        int column = start.get("character").getAsInt() + 1;
        JsonObject object = Jsons.object();
        Jsons.add(object, "file", Jsons.path(path));
        Jsons.add(object, "line", line);
        Jsons.add(object, "column", column);
        Jsons.add(object, "kind", kind);
        return object;
    }

    private JsonArray groupReferencesByCaller(JsonArray references, int limit) {
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (JsonElement element : references) {
            if (groups.size() >= limit) {
                break;
            }
            JsonObject reference = element.getAsJsonObject();
            String enclosing = Jsons.string(reference, "enclosingSymbol", "<unknown>");
            JsonObject group = groups.computeIfAbsent(enclosing, ignored -> {
                JsonObject object = Jsons.object();
                Jsons.add(object, "name", enclosing);
                Jsons.add(object, "kind", "caller");
                object.add("callSites", Jsons.array());
                return object;
            });
            group.getAsJsonArray("callSites").add(reference.deepCopy());
        }
        JsonArray array = Jsons.array();
        for (JsonObject value : groups.values()) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject ok(String command) {
        JsonObject object = Jsons.object();
        Jsons.add(object, "ok", true);
        Jsons.add(object, "command", command);
        return object;
    }

    private static JsonObject error(String code, String message) {
        JsonObject object = Jsons.object();
        Jsons.add(object, "ok", false);
        Jsons.add(object, "error", code);
        Jsons.add(object, "message", message);
        return object;
    }

    private static String firstArg(JsonObject request) {
        JsonArray args = request.getAsJsonArray("args");
        if (args == null || args.size() == 0) {
            throw new IllegalArgumentException("missing required argument");
        }
        return args.get(0).getAsString();
    }

    private static int optionInt(JsonObject request, String name, int defaultValue) {
        JsonObject options = request.has("options") && request.get("options").isJsonObject() ? request.getAsJsonObject("options") : Jsons.object();
        return Jsons.integer(options, name, defaultValue);
    }

    private static boolean optionBool(JsonObject request, String name, boolean defaultValue) {
        JsonObject options = request.has("options") && request.get("options").isJsonObject() ? request.getAsJsonObject("options") : Jsons.object();
        return Jsons.bool(options, name, defaultValue);
    }

    private static String optionString(JsonObject request, String name, String defaultValue) {
        JsonObject options = request.has("options") && request.get("options").isJsonObject() ? request.getAsJsonObject("options") : Jsons.object();
        return Jsons.string(options, name, defaultValue);
    }

    private static void setOption(JsonObject request, String name, boolean value) {
        JsonObject options = request.has("options") && request.get("options").isJsonObject() ? request.getAsJsonObject("options") : Jsons.object();
        Jsons.add(options, name, value);
        request.add("options", options);
    }

    private static String severityName(int severity) {
        return switch (severity) {
            case 1 -> "error";
            case 2 -> "warning";
            case 3 -> "information";
            case 4 -> "hint";
            default -> "unknown";
        };
    }
}
