# jdtls-agent design

## Layers

1. `AgentDaemon` owns one warm `jdtls` child process for a project root and exposes a local loopback TCP JSONL API.
2. `AgentClient` is both a short-lived CLI veneer for human commands and a persistent `rpc` stdin/stdout bridge for tool-loop clients.
3. `JdtlsClient` speaks raw LSP over stdio to `jdtls`.
4. `SourceIndex` parses Java source with the JDK compiler API to resolve agent-friendly symbol strings to file positions.
5. `QueryService` maps CLI commands to LSP requests and normalizes output.

## Daemon/client protocol

The daemon writes a per-project port file under `.jdtls-agent/run/<project-key>.port`. Clients open a loopback TCP connection and send newline-delimited JSON objects. Connections may be one-shot or long-lived; the daemon handles many requests on the same socket.

Two request shapes are accepted:

- legacy command JSON: `{"command":"references","args":["pkg.Type.method"],"options":{"limit":20}}`
- JSON-RPC 2.0: `{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.references","params":{"query":"pkg.Type.method","limit":20}}`

`AgentProtocol` normalizes both shapes into the internal `QueryService` request. JSON-RPC responses wrap the existing stable command response under `result`; protocol-level parse/invalid-method errors use JSON-RPC `error`.

Batch mode sends one outer request containing many parsed command objects. The daemon runs those through the same `QueryService` instance, so all subqueries share the warm JDTLS process and local declaration index.

`jdtls-agent rpc` is a persistent stdin/stdout bridge for LLM clients that prefer launching a process over opening the daemon TCP port directly. `rpc '<json>'` is also supported for one-shot JSON-RPC smoke checks.

## JDTLS process model

`jdtls` is launched once with:

```text
jdtls --jvm-arg=-Duser.home=.jdtls-agent/homes/<project-key> -configuration .jdtls-agent/configurations/<project-key> -data .jdtls-agent/workspaces/<project-key>
```

The workspace, Eclipse configuration, cache, and effective home directories are intentionally outside hack80 source. The daemon sends `initialize`, `initialized`, and `workspace/didChangeConfiguration` with source roots and a Java 21 runtime. For hack80, JDTLS creates an invisible Eclipse project from `java/src` and `java/test`.

## Symbol resolution

LSP references, definitions, and call hierarchy are position-based. Agents usually know names. `SourceIndex` bridges that gap:

- walks configured source roots
- parses each `.java` file with `JavacTask.parse()`
- records class, field, method, constructor, owner, modifiers, line/column, and parameter types
- resolves exact FQNs first, then suffix/simple-name candidates
- treats overloads as ambiguous unless a signature is supplied
- filters test sources when requested

Declaration-position lookup uses this local parser. References/callers/callees remain JDTLS semantic results. `field-writes` is hybrid: JDTLS first finds exact field references, then the local AST classifies whether each reference is in an assignment/compound-assignment/increment target.

## Caching

The declaration index is kept in daemon memory and guarded by source file fingerprints. Each request checks whether the source set or fingerprint map changed and rebuilds the index when needed; `refresh-index` exposes that refresh explicitly for callers.

## Safety

The daemon is read-only. If JDTLS asks the client to apply workspace edits, the daemon responds with `applied=false`. Future refactoring commands should be dry-run first and test apply behavior against a toy Java project before touching active code.

## Implemented supplements

- `field-writes` combines semantic JDTLS references with local AST write-context classification.
- `mutation-map` uses `field-writes` to report externally-owned writes into a class/package scope.
- `api-surface` lists public/static methods under a class/package scope and groups semantic references by enclosing caller.
- caller/callee output stores true `callSiteCount` while truncating displayed `callSites` to `callSiteLimit`.

## Future commands

The current shape leaves room for:

- dry-run `rename`
- `organize-imports`
- file-scoped `format`
