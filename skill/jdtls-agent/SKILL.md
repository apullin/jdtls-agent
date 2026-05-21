---
name: jdtls-agent
description: Use jdtls-agent for fast semantic Java navigation, reference search, call hierarchy, field writes, API surface, and mutation-map queries.
---

# jdtls-agent Skill

## When to use

Use `jdtls-agent` when you need source-level semantic answers for a Java project and plain text search is not reliable enough.

Best fits:

- exact symbol lookup
- exact definitions
- exact semantic references
- callers / callees
- field write discovery
- package/class API surface summaries
- mutation ownership questions
- batch semantic queries for agent loops

## Ground rules

- Prefer fully qualified symbols for decisions.
- Use unqualified symbol fragments for discovery only.
- Treat `callers` / `callees` as strong guidance, not proof of total reachability.
- Treat diagnostics as advisory on weak project models; keep the real build or IDE as compile authority for surprising results.
- After editing Java source outside the daemon's lifetime assumptions, run `refresh-index` or let the daemon auto-refresh on the next request.

## Startup

Start one daemon per project:

```sh
./scripts/jdtls-agentd --project /absolute/path/to/project
```

Quick health check:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project status --json
```

## Best command patterns

### Symbol / definition

```sh
./scripts/jdtls-agent --project /absolute/path/to/project symbol org.example.Type
./scripts/jdtls-agent --project /absolute/path/to/project definition org.example.Type.method
```

### References / callers / callees

```sh
./scripts/jdtls-agent --project /absolute/path/to/project references org.example.Type.method --json
./scripts/jdtls-agent --project /absolute/path/to/project callers org.example.Type.method --json
./scripts/jdtls-agent --project /absolute/path/to/project callees org.example.Type.method --json
```

### Field ownership

```sh
./scripts/jdtls-agent --project /absolute/path/to/project field-writes org.example.Type.field --json
./scripts/jdtls-agent --project /absolute/path/to/project mutation-map org.example.package --limit 10 --call-site-limit 5 --json
```

### API trimming

```sh
./scripts/jdtls-agent --project /absolute/path/to/project api-surface org.example.package --limit 20 --json
```

### Batch mode

```sh
./scripts/jdtls-agent --project /absolute/path/to/project batch queries.txt --jsonl
```

## JSON-RPC mode for agents

Persistent bridge:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project rpc
```

One-shot request:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project rpc '{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.references","params":{"query":"org.example.Type.method","limit":10}}'
```

Useful methods:

- `jdtlsAgent.status`
- `jdtlsAgent.symbol`
- `jdtlsAgent.definition`
- `jdtlsAgent.references`
- `jdtlsAgent.callers`
- `jdtlsAgent.callees`
- `jdtlsAgent.diagnostics`
- `jdtlsAgent.field-writes`
- `jdtlsAgent.api-surface`
- `jdtlsAgent.mutation-map`
- `jdtlsAgent.refresh-index`
- `jdtlsAgent.batch`

## Reading results

- `callSiteCount` is the true number of call sites; `callSites` may be truncated by `--call-site-limit`.
- `diagnosticsPublished=false` means JDTLS has not emitted diagnostics yet for that scope.
- `sourceIndexRefresh.changed=true` means the local declaration index was rebuilt because files changed.

## Failure modes

- `ambiguous-symbol`: refine the symbol to a fully qualified name or include a method signature.
- `symbol-not-found`: check package/class spelling, source roots, or whether the file is generated and not under the configured roots.
- suspicious diagnostics on plain source-root projects: cross-check with the real build.

## Cleanup

If a daemon wrapper or harness exits badly and leaves stale state:

```sh
java -jar target/jdtls-agent-0.1.0-all.jar cleanup --project /absolute/path/to/project
```
