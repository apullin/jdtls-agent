package dev.jdtlsagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AgentClient {
    private AgentClient() {
    }

    static int run(String[] args) throws Exception {
        AgentOptions options;
        try {
            options = AgentOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            return 2;
        }
        if (options.command.isEmpty() || "help".equals(options.command.get(0))) {
            printUsage(System.out);
            return 0;
        }
        String command = options.command.get(0);
        AgentPaths paths = AgentPaths.forProject(options.resolvedProject());
        if ("rpc".equals(command)) {
            return runRpcBridge(paths, options.command.subList(1, options.command.size()));
        }
        JsonObject request;
        try {
            request = buildRequest(command, options.command.subList(1, options.command.size()), options);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        JsonObject response;
        try {
            response = send(paths, request);
        } catch (IOException e) {
            if ("status".equals(command)) {
                response = daemonDownStatus(paths, e.getMessage());
            } else {
                System.err.println("jdtls-agentd is not reachable for " + paths.project + ": " + e.getMessage());
                return 1;
            }
        }
        printResponse(command, response, options);
        return exitCode(response, command);
    }

    static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  jdtls-agentd --project <path> [--jdtls <path>] [--port N]");
        out.println("  jdtls-agent [--project <path>] [--json|--jsonl] [--limit N] <command> [args]");
        out.println();
        out.println("Commands:");
        out.println("  status");
        out.println("  symbol <query>");
        out.println("  definition <symbol>");
        out.println("  references <symbol> [--include-declaration] [--exclude-tests]");
        out.println("  callers <symbol> [--exclude-tests]");
        out.println("  callees <symbol> [--exclude-tests]");
        out.println("  diagnostics [--errors-only] [--file <relative-or-absolute-java-file>]");
        out.println("  field-writes <field-symbol> [--exclude-tests]");
        out.println("  api-surface <package-or-class> [--limit N] [--exclude-tests]");
        out.println("  mutation-map <package-or-class> [--limit N] [--call-site-limit N]");
        out.println("  refresh-index");
        out.println("  batch <file> [--jsonl]");
        out.println("  rpc [json-request]    # persistent stdin/stdout JSON-RPC bridge, or one-shot request");
    }

    private static JsonObject buildRequest(String command, List<String> args, AgentOptions options) throws IOException {
        if ("batch".equals(command)) {
            if (args.isEmpty()) {
                throw new IllegalArgumentException("batch requires a query file");
            }
            Path file = Path.of(args.get(0));
            JsonObject request = baseRequest("batch", List.of(), options);
            JsonArray commands = Jsons.array();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                List<String> tokens = splitCommandLine(stripped);
                if (tokens.isEmpty()) {
                    continue;
                }
                AgentOptions lineOptions = cloneForBatch(options);
                applyBatchTokens(lineOptions, tokens);
                if (lineOptions.command.isEmpty()) {
                    continue;
                }
                commands.add(baseRequest(lineOptions.command.get(0), lineOptions.command.subList(1, lineOptions.command.size()), lineOptions));
            }
            request.add("commands", commands);
            return request;
        }
        return baseRequest(command, args, options);
    }

    private static AgentOptions cloneForBatch(AgentOptions options) {
        AgentOptions clone = new AgentOptions();
        clone.project = options.project;
        clone.jdtls = options.jdtls;
        clone.port = options.port;
        clone.json = options.json;
        clone.jsonl = options.jsonl;
        clone.includeTests = options.includeTests;
        clone.includeDeclaration = options.includeDeclaration;
        clone.errorsOnly = options.errorsOnly;
        clone.limit = options.limit;
        clone.callSiteLimit = options.callSiteLimit;
        clone.sourceRoot = options.sourceRoot;
        clone.testSourceRoot = options.testSourceRoot;
        clone.file = options.file;
        clone.full = options.full;
        return clone;
    }

    private static void applyBatchTokens(AgentOptions options, List<String> tokens) {
        options.command.clear();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "--json" -> options.json = true;
                case "--jsonl" -> options.jsonl = true;
                case "--include-tests" -> options.includeTests = true;
                case "--exclude-tests" -> options.includeTests = false;
                case "--include-declaration" -> options.includeDeclaration = true;
                case "--errors-only" -> options.errorsOnly = true;
                case "--full" -> options.full = true;
                case "--limit" -> options.limit = Integer.parseInt(requireTokenValue(tokens, ++i, token));
                case "--call-site-limit" -> options.callSiteLimit = Integer.parseInt(requireTokenValue(tokens, ++i, token));
                case "--source-root" -> options.sourceRoot = requireTokenValue(tokens, ++i, token);
                case "--test-source-root" -> options.testSourceRoot = requireTokenValue(tokens, ++i, token);
                case "--file" -> options.file = requireTokenValue(tokens, ++i, token);
                default -> options.command.add(token);
            }
        }
    }

    private static String requireTokenValue(List<String> tokens, int index, String flag) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return tokens.get(index);
    }

    private static JsonObject baseRequest(String command, List<String> args, AgentOptions options) {
        requireArgs(command, args);
        JsonObject request = Jsons.object();
        Jsons.add(request, "command", command);
        JsonArray arguments = Jsons.array();
        for (String arg : args) {
            arguments.add(arg);
        }
        request.add("args", arguments);
        JsonObject opts = Jsons.object();
        Jsons.add(opts, "includeTests", options.includeTests);
        Jsons.add(opts, "includeDeclaration", options.includeDeclaration);
        Jsons.add(opts, "errorsOnly", options.errorsOnly);
        Jsons.add(opts, "limit", options.limit);
        Jsons.add(opts, "callSiteLimit", options.callSiteLimit);
        Jsons.add(opts, "sourceRoot", options.sourceRoot);
        Jsons.add(opts, "testSourceRoot", options.testSourceRoot);
        Jsons.add(opts, "file", options.file);
        Jsons.add(opts, "full", options.full);
        request.add("options", opts);
        return request;
    }

    private static void requireArgs(String command, List<String> args) {
        switch (command) {
            case "symbol", "definition", "references", "callers", "callees", "field-writes", "api-surface", "mutation-map" -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException(command + " requires a symbol/query argument");
                }
            }
            case "status", "diagnostics", "batch", "refresh-index" -> {
            }
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
    }
    private static int runRpcBridge(AgentPaths paths, List<String> requestArgs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), daemonPort(paths)), 2_000);
            socket.setSoTimeout(0);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                if (!requestArgs.isEmpty()) {
                    return sendRpcLine(String.join(" ", requestArgs), writer, reader);
                }
                try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stdin.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        int code = sendRpcLine(line, writer, reader);
                        if (code != 0) {
                            return code;
                        }
                    }
                }
                return 0;
            }
        } catch (IOException e) {
            System.err.println("jdtls-agentd is not reachable for " + paths.project + ": " + e.getMessage());
            return 1;
        }
    }

    private static int sendRpcLine(String line, BufferedWriter writer, BufferedReader reader) throws IOException {
        boolean expectsResponse = expectsResponse(line);
        writer.write(line);
        writer.write('\n');
        writer.flush();
        if (expectsResponse) {
            String response = reader.readLine();
            if (response == null) {
                System.err.println("daemon closed connection without a response");
                return 1;
            }
            System.out.println(response);
            System.out.flush();
        }
        return 0;
    }

    private static boolean expectsResponse(String line) {
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            return !AgentProtocol.isJsonRpc(object) || object.has("id");
        } catch (Exception ignored) {
            return true;
        }
    }

    private static int daemonPort(AgentPaths paths) throws IOException {
        if (!Files.isRegularFile(paths.portFile)) {
            throw new IOException("missing port file " + paths.portFile);
        }
        return Integer.parseInt(Files.readString(paths.portFile, StandardCharsets.UTF_8).trim());
    }


    private static JsonObject send(AgentPaths paths, JsonObject request) throws IOException {
        int port = daemonPort(paths);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
            socket.setSoTimeout(120_000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(Jsons.GSON.toJson(request));
                writer.write('\n');
                writer.flush();
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("daemon closed connection without a response");
                }
                return JsonParser.parseString(line).getAsJsonObject();
            }
        }
    }

    private static JsonObject daemonDownStatus(AgentPaths paths, String message) {
        JsonObject object = Jsons.object();
        Jsons.add(object, "ok", true);
        Jsons.add(object, "command", "status");
        Jsons.add(object, "daemonUp", false);
        Jsons.add(object, "project", Jsons.path(paths.project));
        Jsons.add(object, "message", message);
        return object;
    }

    private static void printResponse(String command, JsonObject response, AgentOptions options) {
        if (options.json || ("batch".equals(command) && !options.jsonl)) {
            System.out.println(Jsons.PRETTY_GSON.toJson(response));
            return;
        }
        if (options.jsonl) {
            if ("batch".equals(command) && response.has("results") && response.get("results").isJsonArray()) {
                for (JsonElement element : response.getAsJsonArray("results")) {
                    System.out.println(Jsons.GSON.toJson(element));
                }
            } else {
                System.out.println(Jsons.GSON.toJson(response));
            }
            return;
        }
        if (!Jsons.bool(response, "ok", false)) {
            printError(response);
            return;
        }
        switch (command) {
            case "status" -> printStatus(response);
            case "symbol" -> printSymbols(response);
            case "definition" -> printDefinition(response);
            case "references", "field-writes" -> printReferences(response);
            case "callers", "callees" -> printCalls(response);
            case "api-surface" -> printApiSurface(response);
            case "mutation-map" -> printMutationMap(response);
            case "diagnostics" -> printDiagnostics(response);
            case "batch", "refresh-index" -> System.out.println(Jsons.PRETTY_GSON.toJson(response));
            default -> System.out.println(Jsons.PRETTY_GSON.toJson(response));
        }
    }

    private static int exitCode(JsonObject response, String command) {
        if (!Jsons.bool(response, "ok", false)) {
            return 1;
        }
        if ("batch".equals(command) && response.has("results")) {
            for (JsonElement element : response.getAsJsonArray("results")) {
                if (!Jsons.bool(element.getAsJsonObject(), "ok", false)) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private static void printStatus(JsonObject response) {
        boolean up = Jsons.bool(response, "daemonUp", false);
        System.out.println("daemon: " + (up ? "up" : "down"));
        System.out.println("project: " + Jsons.string(response, "project", ""));
        if (!up) {
            System.out.println("message: " + Jsons.string(response, "message", ""));
            return;
        }
        JsonObject jdtls = response.getAsJsonObject("jdtls");
        System.out.println("jdtls command: " + Jsons.string(jdtls, "jdtlsCommand", ""));
        System.out.println("jdtls pid: " + Jsons.integer(jdtls, "jdtlsPid", 0));
        System.out.println("workspace: " + Jsons.string(jdtls, "workspaceDataDir", ""));
        System.out.println("last status: " + Jsons.string(jdtls, "lastStatus", ""));
        System.out.println("source files: " + Jsons.integer(response, "sourceFiles", 0));
        System.out.println("queries: " + Jsons.integer(response, "queryCount", 0));
        System.out.println("average latency ms: " + Jsons.integer(response, "averageLatencyMs", 0));
    }

    private static void printSymbols(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject symbol = element.getAsJsonObject();
            System.out.println(Jsons.string(symbol, "qualifiedName", ""));
            System.out.println("  " + Jsons.string(symbol, "file", "") + ":" + Jsons.integer(symbol, "line", 0) + " " + Jsons.string(symbol, "kind", ""));
        }
        if (results.size() == 0) {
            System.out.println("no symbols found");
        }
    }

    private static void printDefinition(JsonObject response) {
        JsonObject symbol = response.getAsJsonObject("resolvedSymbol");
        System.out.println(Jsons.string(symbol, "file", "") + ":" + Jsons.integer(symbol, "line", 0) + ":" + Jsons.integer(symbol, "column", 0)
                + " " + Jsons.string(symbol, "kind", "") + " " + Jsons.string(symbol, "qualifiedName", ""));
    }

    private static void printReferences(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject ref = element.getAsJsonObject();
            System.out.println(Jsons.string(ref, "file", "") + ":" + Jsons.integer(ref, "line", 0) + ": " + Jsons.string(ref, "preview", ""));
        }
        if (results.size() == 0) {
            System.out.println("no references found");
        }
    }

    private static void printCalls(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject call = element.getAsJsonObject();
            String name = Jsons.string(call, "name", Jsons.string(call, "detail", ""));
            System.out.println(name + " " + Jsons.string(call, "file", "") + ":" + Jsons.integer(call, "line", 0));
            JsonArray sites = call.has("callSites") ? call.getAsJsonArray("callSites") : Jsons.array();
            for (JsonElement siteElement : sites) {
                JsonObject site = siteElement.getAsJsonObject();
                System.out.println("  " + Jsons.string(site, "file", "") + ":" + Jsons.integer(site, "line", 0) + ": " + Jsons.string(site, "preview", ""));
            }
            int callSiteCount = Jsons.integer(call, "callSiteCount", sites.size());
            if (callSiteCount > sites.size() || Jsons.bool(call, "callSitesTruncated", false)) {
                System.out.println("  ... " + (callSiteCount - sites.size()) + " more call sites");
            }
        }
        if (results.size() == 0) {
            System.out.println("no call hierarchy results found");
        }
        if (response.has("fallback")) {
            System.out.println("fallback: " + response.get("fallback").getAsString());
        }
        if (response.has("warning")) {
            System.out.println("warning: " + response.get("warning").getAsString());
        }
    }

    private static void printApiSurface(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject method = element.getAsJsonObject();
            System.out.println(Jsons.string(method, "qualifiedName", "") + " "
                    + Jsons.string(method, "file", "") + ":" + Jsons.integer(method, "line", 0)
                    + " callers=" + Jsons.integer(method, "callerCount", 0));
            JsonArray callers = method.has("callers") ? method.getAsJsonArray("callers") : Jsons.array();
            for (JsonElement callerElement : callers) {
                JsonObject caller = callerElement.getAsJsonObject();
                System.out.println("  " + Jsons.string(caller, "name", ""));
            }
        }
        if (results.size() == 0) {
            System.out.println("no API surface methods found");
        }
    }

    private static void printMutationMap(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject item = element.getAsJsonObject();
            JsonObject field = item.getAsJsonObject("field");
            System.out.println(Jsons.string(field, "qualifiedName", "") + " externalWrites="
                    + Jsons.integer(item, "externalWriteCount", 0));
            JsonArray writes = item.has("externalWrites") ? item.getAsJsonArray("externalWrites") : Jsons.array();
            for (JsonElement writeElement : writes) {
                JsonObject write = writeElement.getAsJsonObject();
                System.out.println("  " + Jsons.string(write, "file", "") + ":" + Jsons.integer(write, "line", 0)
                        + ": " + Jsons.string(write, "preview", ""));
            }
            if (Jsons.bool(item, "externalWritesTruncated", false)) {
                System.out.println("  ... truncated");
            }
        }
        if (results.size() == 0) {
            System.out.println("no external field writes found");
        }
    }

    private static void printDiagnostics(JsonObject response) {
        JsonArray results = response.getAsJsonArray("results");
        for (JsonElement element : results) {
            JsonObject diagnostic = element.getAsJsonObject();
            System.out.println(Jsons.string(diagnostic, "file", "") + ":" + Jsons.integer(diagnostic, "line", 0) + ": "
                    + Jsons.string(diagnostic, "severity", "") + ": " + Jsons.string(diagnostic, "message", ""));
        }
        if (results.size() == 0) {
            System.out.println(Jsons.string(response, "message", "no diagnostics"));
        }
    }

    private static void printError(JsonObject response) {
        System.err.println(Jsons.string(response, "error", "error") + ": " + Jsons.string(response, "message", ""));
        if (response.has("candidates") && response.get("candidates").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("candidates")) {
                JsonObject candidate = element.getAsJsonObject();
                System.err.println("  " + Jsons.string(candidate, "qualifiedName", "") + " "
                        + Jsons.string(candidate, "file", "") + ":" + Jsons.integer(candidate, "line", 0));
            }
        }
    }

    private static List<String> splitCommandLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !dbl) {
                single = !single;
            } else if (ch == '"' && !single) {
                dbl = !dbl;
            } else if (Character.isWhitespace(ch) && !single && !dbl) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
