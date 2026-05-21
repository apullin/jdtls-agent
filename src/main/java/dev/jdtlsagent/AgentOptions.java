package dev.jdtlsagent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AgentOptions {
    static final Path DEFAULT_PROJECT = Path.of("/Users/andrewpullin/personal/nethack/hack80");
    static final String DEFAULT_SOURCE_ROOT = "java/src";
    static final String DEFAULT_TEST_SOURCE_ROOT = "java/test";
    static final int DEFAULT_LIMIT = 50;
    static final int DEFAULT_CALL_SITE_LIMIT = 10;

    Path project = DEFAULT_PROJECT;
    String jdtls;
    int port = 0;
    boolean json;
    boolean jsonl;
    boolean includeTests = true;
    boolean includeDeclaration;
    boolean errorsOnly;
    int limit = DEFAULT_LIMIT;
    int callSiteLimit = DEFAULT_CALL_SITE_LIMIT;
    String sourceRoot = DEFAULT_SOURCE_ROOT;
    String testSourceRoot = DEFAULT_TEST_SOURCE_ROOT;
    String file;
    boolean full;
    final List<String> command = new ArrayList<>();

    Path resolvedProject() {
        return project.toAbsolutePath().normalize();
    }

    static AgentOptions parse(String[] args) {
        AgentOptions options = new AgentOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--project" -> options.project = Path.of(requireValue(args, ++i, arg));
                case "--jdtls" -> options.jdtls = requireValue(args, ++i, arg);
                case "--port" -> options.port = Integer.parseInt(requireValue(args, ++i, arg));
                case "--json" -> options.json = true;
                case "--jsonl" -> options.jsonl = true;
                case "--include-tests" -> options.includeTests = true;
                case "--exclude-tests" -> options.includeTests = false;
                case "--include-declaration" -> options.includeDeclaration = true;
                case "--errors-only" -> options.errorsOnly = true;
                case "--limit" -> options.limit = Integer.parseInt(requireValue(args, ++i, arg));
                case "--call-site-limit" -> options.callSiteLimit = Integer.parseInt(requireValue(args, ++i, arg));
                case "--source-root" -> options.sourceRoot = requireValue(args, ++i, arg);
                case "--test-source-root" -> options.testSourceRoot = requireValue(args, ++i, arg);
                case "--file" -> options.file = requireValue(args, ++i, arg);
                case "--full" -> options.full = true;
                case "--help", "-h" -> options.command.add("help");
                default -> options.command.add(arg);
            }
        }
        return options;
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }
}
