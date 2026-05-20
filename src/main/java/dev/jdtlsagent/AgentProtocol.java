package dev.jdtlsagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class AgentProtocol {
    private AgentProtocol() {
    }

    static JsonObject handleLine(String line, QueryService queries) {
        JsonObject inbound;
        try {
            inbound = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            return jsonRpcError(JsonNull.INSTANCE, -32700, "Parse error: " + message(e));
        }
        try {
            return handle(inbound, queries);
        } catch (ProtocolException e) {
            if (isJsonRpc(inbound)) {
                return jsonRpcError(idOf(inbound), e.code(), e.getMessage());
            }
            JsonObject error = Jsons.object();
            Jsons.add(error, "ok", false);
            Jsons.add(error, "error", e.error());
            Jsons.add(error, "message", e.getMessage());
            return error;
        } catch (Exception e) {
            if (isJsonRpc(inbound)) {
                return jsonRpcError(idOf(inbound), -32603, "Internal error: " + message(e));
            }
            JsonObject error = Jsons.object();
            Jsons.add(error, "ok", false);
            Jsons.add(error, "error", "daemon-handler-failed");
            Jsons.add(error, "message", message(e));
            return error;
        }
    }

    static JsonObject handle(JsonObject inbound, QueryService queries) {
        if (!isJsonRpc(inbound)) {
            if (!inbound.has("command")) {
                throw new ProtocolException(-32600, "invalid-request", "Request must contain either command or JSON-RPC method");
            }
            return queries.execute(inbound);
        }
        JsonElement id = idOf(inbound);
        if (!inbound.has("id")) {
            queries.execute(toServiceRequest(inbound));
            return null;
        }
        JsonObject result = queries.execute(toServiceRequest(inbound));
        JsonObject response = Jsons.object();
        Jsons.add(response, "jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    static JsonObject toServiceRequest(JsonObject rpc) {
        if (!isJsonRpc(rpc)) {
            if (!rpc.has("command")) {
                throw new ProtocolException(-32600, "invalid-request", "Request must contain command");
            }
            return rpc.deepCopy();
        }
        String command = normalizeMethod(Jsons.string(rpc, "method", ""));
        JsonElement paramsElement = rpc.get("params");
        JsonObject params = paramsElement != null && paramsElement.isJsonObject() ? paramsElement.getAsJsonObject() : Jsons.object();
        JsonArray paramArray = paramsElement != null && paramsElement.isJsonArray() ? paramsElement.getAsJsonArray() : null;

        JsonObject request = Jsons.object();
        Jsons.add(request, "command", command);
        JsonArray args = Jsons.array();
        if (paramArray != null) {
            for (JsonElement element : paramArray) {
                args.add(element.getAsString());
            }
        } else if (params.has("args") && params.get("args").isJsonArray()) {
            for (JsonElement element : params.getAsJsonArray("args")) {
                args.add(element.getAsString());
            }
        } else {
            String argument = firstString(params, "query", "symbol", "name");
            if (argument != null) {
                args.add(argument);
            }
        }
        request.add("args", args);
        request.add("options", optionsFromParams(params));
        if ("batch".equals(command)) {
            request.add("commands", batchCommands(params));
        }
        return request;
    }

    static JsonObject jsonRpcError(JsonElement id, int code, String message) {
        JsonObject response = Jsons.object();
        Jsons.add(response, "jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject error = Jsons.object();
        Jsons.add(error, "code", code);
        Jsons.add(error, "message", message);
        response.add("error", error);
        return response;
    }

    static boolean isJsonRpc(JsonObject object) {
        return object.has("jsonrpc") || object.has("method");
    }

    private static JsonArray batchCommands(JsonObject params) {
        JsonArray commands = Jsons.array();
        if (!params.has("commands") || !params.get("commands").isJsonArray()) {
            return commands;
        }
        for (JsonElement element : params.getAsJsonArray("commands")) {
            if (!element.isJsonObject()) {
                throw new ProtocolException(-32602, "invalid-params", "batch commands must be objects");
            }
            commands.add(toServiceRequest(element.getAsJsonObject()));
        }
        return commands;
    }

    private static JsonObject optionsFromParams(JsonObject params) {
        JsonObject options = Jsons.object();
        if (params.has("options") && params.get("options").isJsonObject()) {
            options = params.getAsJsonObject("options").deepCopy();
        }
        copyOption(params, options, "includeTests");
        copyOption(params, options, "includeDeclaration");
        copyOption(params, options, "errorsOnly");
        copyOption(params, options, "limit");
        copyOption(params, options, "sourceRoot");
        copyOption(params, options, "testSourceRoot");
        copyOption(params, options, "file");
        copyOption(params, options, "full");
        return options;
    }

    private static void copyOption(JsonObject params, JsonObject options, String name) {
        if (params.has(name)) {
            options.add(name, params.get(name).deepCopy());
        }
    }

    private static String normalizeMethod(String method) {
        String normalized = method;
        if (normalized.startsWith("jdtlsAgent.")) {
            normalized = normalized.substring("jdtlsAgent.".length());
        } else if (normalized.startsWith("jdtlsAgent/")) {
            normalized = normalized.substring("jdtlsAgent/".length());
        } else if (normalized.startsWith("jdtls-agent.")) {
            normalized = normalized.substring("jdtls-agent.".length());
        } else if (normalized.startsWith("jdtls-agent/")) {
            normalized = normalized.substring("jdtls-agent/".length());
        }
        return switch (normalized) {
            case "status", "symbol", "definition", "references", "callers", "callees", "diagnostics", "field-writes", "api-surface", "batch" -> normalized;
            default -> throw new ProtocolException(-32601, "method-not-found", "Unknown method: " + method);
        };
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
        }
        return null;
    }

    private static JsonElement idOf(JsonObject object) {
        JsonElement id = object.get("id");
        return id == null ? JsonNull.INSTANCE : id.deepCopy();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static final class ProtocolException extends RuntimeException {
        private final int code;
        private final String error;

        ProtocolException(int code, String error, String message) {
            super(message);
            this.code = code;
            this.error = error;
        }

        int code() {
            return code;
        }

        String error() {
            return error;
        }
    }
}
