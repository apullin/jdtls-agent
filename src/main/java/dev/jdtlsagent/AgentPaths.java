package dev.jdtlsagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class AgentPaths {
    final Path project;
    final Path baseDir;
    final Path workspaceDir;
    final Path configurationDir;
    final Path homeDir;
    final Path cacheDir;
    final Path logDir;
    final Path runDir;
    final Path portFile;
    final Path pidFile;
    final Path jdtlsPidFile;
    final String projectKey;

    private AgentPaths(Path project, Path baseDir, String projectKey) {
        this.project = project;
        this.baseDir = baseDir;
        this.workspaceDir = baseDir.resolve("workspaces").resolve(projectKey);
        this.configurationDir = baseDir.resolve("configurations").resolve(projectKey);
        this.homeDir = baseDir.resolve("homes").resolve(projectKey);
        this.cacheDir = baseDir.resolve("caches").resolve(projectKey);
        this.logDir = baseDir.resolve("logs");
        this.runDir = baseDir.resolve("run");
        this.portFile = runDir.resolve(projectKey + ".port");
        this.pidFile = runDir.resolve(projectKey + ".pid");
        this.jdtlsPidFile = runDir.resolve(projectKey + ".jdtls.pid");
        this.projectKey = projectKey;
    }

    static AgentPaths forProject(Path project) {
        Path normalized = project.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path base = parent == null ? normalized.resolve(".jdtls-agent") : parent.resolve(".jdtls-agent");
        return new AgentPaths(normalized, base, key(normalized));
    }

    void createDirectories() throws IOException {
        Files.createDirectories(workspaceDir);
        Files.createDirectories(configurationDir);
        Files.createDirectories(homeDir);
        Files.createDirectories(cacheDir);
        Files.createDirectories(logDir);
        Files.createDirectories(runDir);
    }

    private static String key(Path project) {
        String name = project.getFileName() == null ? "project" : project.getFileName().toString();
        String sanitized = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        byte[] bytes;
        try {
            bytes = MessageDigest.getInstance("SHA-256").digest(project.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return sanitized + "-" + HexFormat.of().formatHex(bytes, 0, 4);
    }
}
