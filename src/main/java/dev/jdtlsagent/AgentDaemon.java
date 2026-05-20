package dev.jdtlsagent;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;


final class AgentDaemon {
    private AgentDaemon() {
    }

    static int run(String[] args) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        AgentPaths paths = AgentPaths.forProject(options.resolvedProject());
        paths.createDirectories();
        if (isExistingDaemonAlive(paths)) {
            System.out.println("jdtls-agentd already running for " + paths.project + " on port " + Files.readString(paths.portFile).trim());
            return 0;
        }

        SourceIndex sourceIndex = SourceIndex.build(paths.project, options.sourceRoot, options.testSourceRoot);
        try (JdtlsClient jdtls = JdtlsClient.start(paths, options, sourceIndex);
             ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port));
            int port = server.getLocalPort();
            Files.writeString(paths.portFile, Integer.toString(port), StandardCharsets.UTF_8);
            Files.writeString(paths.pidFile, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            System.out.println("jdtls-agentd listening on 127.0.0.1:" + port + " for " + paths.project);
            System.out.flush();
            QueryService queries = new QueryService(paths, options, sourceIndex, jdtls);
            Runnable shutdown = () -> {
                jdtls.close();
                cleanup(paths);
            };
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "jdtls-agent-cleanup"));
            installSignalHandlers(shutdown);
            while (jdtls.alive()) {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> handle(socket, queries), "jdtls-agent-client");
                handler.setDaemon(true);
                handler.start();
            }
            return 1;
        } finally {
            cleanup(paths);
        }
    }

    static int cleanupRun(String[] args) {
        AgentOptions options = AgentOptions.parse(args);
        AgentPaths paths = AgentPaths.forProject(options.resolvedProject());
        killPidFile(paths.jdtlsPidFile);
        killPidFile(paths.pidFile);
        cleanup(paths);
        return 0;
    }


    private static void handle(Socket socket, QueryService queries) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject response = AgentProtocol.handleLine(line, queries);
                if (response == null) {
                    continue;
                }
                writer.write(Jsons.GSON.toJson(response));
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception e) {
            try {
                JsonObject response = Jsons.object();
                Jsons.add(response, "ok", false);
                Jsons.add(response, "error", "daemon-handler-failed");
                Jsons.add(response, "message", e.getMessage() == null ? e.toString() : e.getMessage());
                socket.getOutputStream().write((Jsons.GSON.toJson(response) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isExistingDaemonAlive(AgentPaths paths) {
        try {
            if (!Files.isRegularFile(paths.portFile)) {
                return false;
            }
            int port = Integer.parseInt(Files.readString(paths.portFile).trim());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }


    private static void installSignalHandlers(Runnable shutdown) {
        for (String signalName : new String[]{"INT", "TERM", "HUP"}) {
            try {
                Class<?> signalClass = Class.forName("sun.misc.Signal");
                Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
                Object signal = signalClass.getConstructor(String.class).newInstance(signalName);
                Object handler = java.lang.reflect.Proxy.newProxyInstance(
                        AgentDaemon.class.getClassLoader(),
                        new Class<?>[]{handlerClass},
                        (proxy, method, args) -> {
                            shutdown.run();
                            System.exit(0);
                            return null;
                        });
                signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Shutdown hook still handles ordinary JVM termination.
            }
        }
    }

    private static void killPidFile(java.nio.file.Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) {
                return;
            }
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            if (pid == ProcessHandle.current().pid()) {
                return;
            }
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.destroy();
                try {
                    handle.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void cleanup(AgentPaths paths) {
        try {
            Files.deleteIfExists(paths.portFile);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(paths.pidFile);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(paths.jdtlsPidFile);
        } catch (IOException ignored) {
        }
    }
}
