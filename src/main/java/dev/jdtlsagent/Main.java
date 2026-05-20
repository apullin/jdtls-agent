package dev.jdtlsagent;

import java.util.Arrays;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            AgentClient.printUsage(System.err);
            System.exit(2);
        }
        String mode = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int exitCode;
        switch (mode) {
            case "daemon" -> exitCode = AgentDaemon.run(rest);
            case "client" -> exitCode = AgentClient.run(rest);
            case "cleanup" -> exitCode = AgentDaemon.cleanupRun(rest);
            default -> exitCode = AgentClient.run(args);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
