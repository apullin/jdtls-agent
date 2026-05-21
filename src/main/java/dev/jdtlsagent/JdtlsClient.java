package dev.jdtlsagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.ProcessHandle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class JdtlsClient implements AutoCloseable {
    private final AgentPaths paths;
    private final AgentOptions options;
    private final SourceIndex sourceIndex;
    private final Process process;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;
    private final PrintWriter log;
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, JsonArray> diagnostics = new ConcurrentHashMap<>();
    private final Set<Path> openedDocuments = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private final JsonObject javaSettings;
    private final String commandLine;
    private volatile String lastStatus = "starting";
    private volatile boolean running = true;

    private JdtlsClient(AgentPaths paths, AgentOptions options, SourceIndex sourceIndex, Process process, PrintWriter log, String commandLine) {
        this.paths = paths;
        this.options = options;
        this.sourceIndex = sourceIndex;
        this.process = process;
        this.in = new BufferedInputStream(process.getInputStream());
        this.out = new BufferedOutputStream(process.getOutputStream());
        this.log = log;
        this.commandLine = commandLine;
        this.javaSettings = buildJavaSettings(options);
    }

    static JdtlsClient start(AgentPaths paths, AgentOptions options, SourceIndex sourceIndex) throws IOException {
        paths.createDirectories();
        String jdtls = locateJdtls(options.jdtls);
        Path stderrLog = paths.logDir.resolve(paths.projectKey + ".jdtls.stderr.log");
        Path agentLog = paths.logDir.resolve(paths.projectKey + ".agent.log");
        List<String> command = List.of(
        // Keep Eclipse workspace/config/home/cache state project-local and disposable.
                jdtls,
                "--jvm-arg=-Duser.home=" + paths.homeDir,
                "--jvm-arg=-Dosgi.configuration.area=" + paths.configurationDir.toUri(),
                "-configuration", paths.configurationDir.toString(),
                "-data", paths.workspaceDir.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(paths.project.toFile());
        builder.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));
        configureLaunchEnvironment(builder, paths);
        Process process = builder.start();
        Files.writeString(paths.jdtlsPidFile, Long.toString(process.pid()), StandardCharsets.UTF_8);
        PrintWriter log = new PrintWriter(Files.newBufferedWriter(agentLog, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
        JdtlsClient client = new JdtlsClient(paths, options, sourceIndex, process, log, String.join(" ", command));
        client.log("starting jdtls command=" + client.commandLine);
        client.log("project=" + paths.project);
        client.log("workspace=" + paths.workspaceDir);
        client.log("pid=" + process.pid());
        Thread reader = new Thread(client::readLoop, "jdtls-agent-lsp-reader");
        reader.setDaemon(true);
        reader.start();
        client.initialize();
        return client;
    }

    long pid() {
        return process.pid();
    }

    String commandLine() {
        return commandLine;
    }

    String lastStatus() {
        return lastStatus;
    }

    boolean alive() {
        return process.isAlive();
    }

    JsonElement request(String method, JsonObject params, Duration timeout) throws Exception {
        long id = nextId.getAndIncrement();
        JsonObject message = Jsons.object();
        Jsons.add(message, "jsonrpc", "2.0");
        Jsons.add(message, "id", id);
        Jsons.add(message, "method", method);
        message.add("params", params == null ? JsonNull.INSTANCE : params);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        send(message);
        JsonObject response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("LSP request timed out: " + method, e);
        }
        JsonElement error = response.get("error");
        if (error != null && !error.isJsonNull()) {
            throw new IOException("LSP error from " + method + ": " + error);
        }
        JsonElement result = response.get("result");
        return result == null ? JsonNull.INSTANCE : result;
    }

    void notify(String method, JsonObject params) throws IOException {
        JsonObject message = Jsons.object();
        Jsons.add(message, "jsonrpc", "2.0");
        Jsons.add(message, "method", method);
        message.add("params", params == null ? JsonNull.INSTANCE : params);
        send(message);
    }

    void didOpen(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!openedDocuments.add(normalized)) {
            return;
        }
        JsonObject textDocument = Jsons.object();
        Jsons.add(textDocument, "uri", normalized.toUri().toString());
        Jsons.add(textDocument, "languageId", "java");
        Jsons.add(textDocument, "version", 1);
        Jsons.add(textDocument, "text", Files.readString(normalized, StandardCharsets.UTF_8));
        JsonObject params = Jsons.object();
        params.add("textDocument", textDocument);
        notify("textDocument/didOpen", params);
    }

    JsonArray diagnostics(Path file) {
        if (file != null) {
            JsonArray array = diagnostics.get(file.toAbsolutePath().normalize().toUri().toString());
            return array == null ? Jsons.array() : array.deepCopy();
        }
        JsonArray all = Jsons.array();
        for (Map.Entry<String, JsonArray> entry : diagnostics.entrySet()) {
            for (JsonElement element : entry.getValue()) {
                JsonObject diagnostic = element.getAsJsonObject().deepCopy();
                Jsons.add(diagnostic, "uri", entry.getKey());
                all.add(diagnostic);
            }
        }
        return all;
    }

    boolean diagnosticsPublished(Path file) {
        if (file != null) {
            return diagnostics.containsKey(file.toAbsolutePath().normalize().toUri().toString());
        }
        return !diagnostics.isEmpty();
    }

    JsonObject statusJson() {
        JsonObject object = Jsons.object();
        Jsons.add(object, "jdtlsCommand", commandLine);
        Jsons.add(object, "jdtlsPid", process.pid());
        Jsons.add(object, "workspaceDataDir", Jsons.path(paths.workspaceDir));
        Jsons.add(object, "configurationDir", Jsons.path(paths.configurationDir));
        Jsons.add(object, "lastStatus", lastStatus);
        Jsons.add(object, "alive", process.isAlive());
        Jsons.add(object, "diagnosticFiles", diagnostics.size());
        return object;
    }

    private void initialize() throws IOException {
        JsonObject capabilities = Jsons.object();
        JsonObject textDocument = Jsons.object();
        textDocument.add("definition", dynamicFalse());
        textDocument.add("declaration", dynamicFalse());
        textDocument.add("references", dynamicFalse());
        textDocument.add("documentSymbol", dynamicFalse());
        textDocument.add("callHierarchy", dynamicFalse());
        JsonObject publishDiagnostics = Jsons.object();
        Jsons.add(publishDiagnostics, "relatedInformation", true);
        textDocument.add("publishDiagnostics", publishDiagnostics);
        capabilities.add("textDocument", textDocument);
        JsonObject workspace = Jsons.object();
        workspace.add("symbol", dynamicFalse());
        Jsons.add(workspace, "workspaceFolders", true);
        Jsons.add(workspace, "configuration", true);
        capabilities.add("workspace", workspace);
        JsonObject window = Jsons.object();
        Jsons.add(window, "workDoneProgress", true);
        capabilities.add("window", window);

        JsonObject params = Jsons.object();
        Jsons.add(params, "processId", ProcessHandle.current().pid());
        Jsons.add(params, "rootUri", paths.project.toUri().toString());
        Jsons.add(params, "rootPath", paths.project.toString());
        params.add("capabilities", capabilities);
        JsonArray folders = Jsons.array();
        JsonObject folder = Jsons.object();
        Jsons.add(folder, "uri", paths.project.toUri().toString());
        Jsons.add(folder, "name", paths.project.getFileName() == null ? "project" : paths.project.getFileName().toString());
        folders.add(folder);
        params.add("workspaceFolders", folders);
        JsonObject initializationOptions = Jsons.object();
        initializationOptions.add("settings", javaSettings.deepCopy());
        initializationOptions.add("bundles", Jsons.array());
        params.add("initializationOptions", initializationOptions);

        try {
            request("initialize", params, Duration.ofSeconds(90));
            notify("initialized", Jsons.object());
            JsonObject settingsParams = Jsons.object();
            settingsParams.add("settings", javaSettings.deepCopy());
            notify("workspace/didChangeConfiguration", settingsParams);
            lastStatus = "initialized";
        } catch (Exception e) {
            throw new IOException("failed to initialize jdtls", e);
        }
    }

    private static JsonObject dynamicFalse() {
        JsonObject object = Jsons.object();
        Jsons.add(object, "dynamicRegistration", false);
        return object;
    }

    private void readLoop() {
        while (running) {
            try {
                JsonObject message = readMessage();
                handleMessage(message);
            } catch (EOFException e) {
                running = false;
                lastStatus = "jdtls-exited";
                completeAll(e);
                return;
            } catch (Exception e) {
                log("read-loop error " + e);
            }
        }
    }

    private JsonObject readMessage() throws IOException {
        String header = readHeader();
        int length = -1;
        for (String line : header.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (length < 0) {
            throw new IOException("LSP message without Content-Length");
        }
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("unexpected EOF in LSP body");
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private String readHeader() throws IOException {
        byte[] marker = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        ArrayList<Byte> bytes = new ArrayList<>();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            bytes.add((byte) b);
            if (b == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    break;
                }
            } else {
                matched = b == marker[0] ? 1 : 0;
            }
        }
        byte[] raw = new byte[bytes.size() - marker.length];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = bytes.get(i);
        }
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private void handleMessage(JsonObject message) throws IOException {
        JsonElement idElement = message.get("id");
        if (idElement != null && message.has("method")) {
            handleServerRequest(message);
            return;
        }
        if (idElement != null) {
            long id = idElement.getAsLong();
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) {
                future.complete(message);
            }
            return;
        }
        JsonElement methodElement = message.get("method");
        if (methodElement == null) {
            return;
        }
        String method = methodElement.getAsString();
        JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : Jsons.object();
        switch (method) {
            case "textDocument/publishDiagnostics" -> {
                String uri = Jsons.string(params, "uri", "");
                JsonArray array = params.has("diagnostics") && params.get("diagnostics").isJsonArray() ? params.getAsJsonArray("diagnostics") : Jsons.array();
                diagnostics.put(uri, array.deepCopy());
            }
            case "language/status" -> lastStatus = params.toString();
            case "window/logMessage", "$/logTrace" -> log("notification " + method + " " + params);
            case "$/progress" -> lastStatus = params.toString();
            default -> {
                if (!method.startsWith("$/")) {
                    log("notification " + method + " " + params);
                }
            }
        }
    }

    private void handleServerRequest(JsonObject message) throws IOException {
        JsonElement id = message.get("id");
        String method = Jsons.string(message, "method", "");
        JsonElement result;
        switch (method) {
            case "client/registerCapability", "window/workDoneProgress/create" -> result = JsonNull.INSTANCE;
            case "workspace/workspaceFolders" -> result = workspaceFolders();
            case "workspace/configuration" -> result = configurationResponse(message.getAsJsonObject("params"));
            case "java/projectConfiguration/isTestFile" -> result = new com.google.gson.JsonPrimitive(isTestFileRequest(message.get("params")));
            case "workspace/applyEdit" -> {
                JsonObject rejected = Jsons.object();
                Jsons.add(rejected, "applied", false);
                Jsons.add(rejected, "failureReason", "jdtls-agent is read-only");
                result = rejected;
            }
            default -> {
                log("unhandled server request " + method + " params=" + message.get("params"));
                result = JsonNull.INSTANCE;
            }
        }
        JsonObject response = Jsons.object();
        Jsons.add(response, "jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result == null ? JsonNull.INSTANCE : result);
        send(response);
    }

    private JsonArray configurationResponse(JsonObject params) {
        JsonArray response = Jsons.array();
        JsonArray items = params != null && params.has("items") && params.get("items").isJsonArray() ? params.getAsJsonArray("items") : Jsons.array();
        for (JsonElement itemElement : items) {
            String section = itemElement.isJsonObject() ? Jsons.string(itemElement.getAsJsonObject(), "section", "") : "";
            response.add(sectionValue(section));
        }
        return response;
    }

    private JsonElement sectionValue(String section) {
        if (section == null || section.isEmpty()) {
            return javaSettings.deepCopy();
        }
        String[] parts = section.split("\\.");
        JsonElement current = javaSettings;
        for (String part : parts) {
            if (!current.isJsonObject()) {
                return JsonNull.INSTANCE;
            }
            JsonObject object = current.getAsJsonObject();
            current = object.get(part);
            if (current == null) {
                return JsonNull.INSTANCE;
            }
        }
        return current.deepCopy();
    }

    private JsonArray workspaceFolders() {
        JsonArray folders = Jsons.array();
        JsonObject folder = Jsons.object();
        Jsons.add(folder, "uri", paths.project.toUri().toString());
        Jsons.add(folder, "name", paths.project.getFileName() == null ? "project" : paths.project.getFileName().toString());
        folders.add(folder);
        return folders;
    }

    private boolean isTestFileRequest(JsonElement params) {
        if (params == null || params.isJsonNull()) {
            return false;
        }
        String uri = null;
        if (params.isJsonObject()) {
            JsonObject object = params.getAsJsonObject();
            uri = Jsons.string(object, "uri", null);
            if (uri == null && object.has("textDocument") && object.get("textDocument").isJsonObject()) {
                uri = Jsons.string(object.getAsJsonObject("textDocument"), "uri", null);
            }
        } else if (params.isJsonPrimitive()) {
            uri = params.getAsString();
        }
        if (uri == null) {
            return false;
        }
        try {
            return sourceIndex.isTestFile(Path.of(URI.create(uri)));
        } catch (Exception e) {
            return false;
        }
    }

    private void send(JsonObject message) throws IOException {
        byte[] body = Jsons.GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            out.write(header);
            out.write(body);
            out.flush();
        }
    }

    private void completeAll(Exception exception) {
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(exception);
        }
        pending.clear();
    }

    private synchronized void log(String message) {
        log.println(Instant.now() + " " + message);
    }

    private static JsonObject buildJavaSettings(AgentOptions options) {
        JsonObject root = Jsons.object();
        JsonObject java = Jsons.object();
        JsonObject project = Jsons.object();
        JsonArray sourcePaths = Jsons.array();
        if (options.sourceRoot != null && !options.sourceRoot.isBlank()) {
            sourcePaths.add(options.sourceRoot);
        }
        if (options.testSourceRoot != null && !options.testSourceRoot.isBlank()) {
            sourcePaths.add(options.testSourceRoot);
        }
        project.add("sourcePaths", sourcePaths);
        Jsons.add(project, "outputPath", ".jdtls-agent/build/classes");
        project.add("referencedLibraries", Jsons.array());
        java.add("project", project);
        JsonObject importSettings = Jsons.object();
        JsonObject gradle = Jsons.object();
        Jsons.add(gradle, "enabled", false);
        importSettings.add("gradle", gradle);
        JsonObject maven = Jsons.object();
        Jsons.add(maven, "enabled", false);
        importSettings.add("maven", maven);
        java.add("import", importSettings);
        JsonObject config = Jsons.object();
        JsonArray runtimes = Jsons.array();
        String javaHome = jdk21Home().orElse(null);
        if (javaHome != null) {
            JsonObject runtime = Jsons.object();
            Jsons.add(runtime, "name", "JavaSE-21");
            Jsons.add(runtime, "path", javaHome);
            Jsons.add(runtime, "default", true);
            runtimes.add(runtime);
        }
        config.add("runtimes", runtimes);
        java.add("configuration", config);
        root.add("java", java);
        return root;
    }

    private static String locateJdtls(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String env = System.getenv("JDTLS_AGENT_JDTLS");
        if (env != null && !env.isBlank()) {
            return env;
        }
        if (Files.isExecutable(Path.of("/opt/homebrew/bin/jdtls"))) {
            return "/opt/homebrew/bin/jdtls";
        }
        return "jdtls";
    }

    private static void configureLaunchEnvironment(ProcessBuilder builder, AgentPaths paths) {
        Map<String, String> env = builder.environment();
        env.put("HOME", paths.homeDir.toString());
        env.put("XDG_CACHE_HOME", paths.cacheDir.toString());
        configureJavaHome(builder);
    }

    private static void configureJavaHome(ProcessBuilder builder) {
        Optional<String> home = jdk21Home();
        if (home.isEmpty()) {
            return;
        }
        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", home.get());
        String bin = Path.of(home.get()).resolve("bin").toString();
        String path = env.getOrDefault("PATH", "");
        if (!Arrays.asList(path.split(java.io.File.pathSeparator)).contains(bin)) {
            env.put("PATH", bin + java.io.File.pathSeparator + path);
        }
    }

    private static Optional<String> jdk21Home() {
        List<Path> candidates = List.of(
                Path.of("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
                Path.of("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
                Path.of("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"),
                Path.of("/opt/homebrew/opt/openjdk@21"),
                Path.of("/opt/homebrew/opt/openjdk")
        );
        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate.resolve("bin/java"))) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        log("closing jdtls");
        try {
            request("shutdown", Jsons.object(), Duration.ofSeconds(3));
        } catch (Exception ignored) {
        }
        try {
            notify("exit", Jsons.object());
        } catch (Exception ignored) {
        }
        destroyProcessTree(false);
        // JDTLS can spawn a JVM child; tear down the whole process tree on exit.
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            destroyProcessTree(true);
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log("closed");
        log.close();
    }

    private void destroyProcessTree(boolean forcibly) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(child -> {
            if (forcibly) {
                child.destroyForcibly();
            } else {
                child.destroy();
            }
        });
        if (forcibly) {
            handle.destroyForcibly();
        } else {
            handle.destroy();
        }
    }
}
