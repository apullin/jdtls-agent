# jdtls-agent

`jdtls-agent` is a small command-line bridge to Eclipse JDT Language Server for Java code archaeology. It keeps one `jdtls` process warm per project, then answers cheap CLI queries for symbols, definitions, references, callers, callees, diagnostics, and batch command files.

The first target is the Hack80 port at `/Users/andrewpullin/personal/nethack/hack80`, but the tool accepts any Java project path.

## Build

The agent jar targets Java 17 bytecode so local Maven runs can use the machine default JDK 20. `jdtls` itself still requires Java 21; the daemon scripts prefer Homebrew OpenJDK 21 automatically when launching it.

```sh
cd /Users/andrewpullin/personal/nethack/jdtls-agent
mvn -q test
mvn -q -DskipTests package
```

The scripts run `target/jdtls-agent-0.1.0-all.jar`.

## Start the daemon

```sh
./scripts/jdtls-agentd --project /Users/andrewpullin/personal/nethack/hack80
```

The daemon:

- starts `/opt/homebrew/bin/jdtls` by default, or `$JDTLS_AGENT_JDTLS`, or `--jdtls <path>`
- forces a Java 21 runtime for `jdtls` when Homebrew OpenJDK 21 is available
- stores Eclipse/JDTLS workspace, configuration, cache, and home state outside hack80 under `.jdtls-agent/`
- writes logs under `.jdtls-agent/logs/`
- writes its loopback TCP port under `.jdtls-agent/run/`

## CLI examples

```sh
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 status
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 symbol AppliedTools --limit 10
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 definition org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 references org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 callers org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 callees org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 diagnostics --errors-only --file java/src/org/hack80/engine/movement/AppliedTools.java
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 field-writes org.hack80.engine.Obj.where --limit 10
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 api-surface org.hack80.engine.movement.AppliedTools --limit 10
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 mutation-map org.hack80.engine --limit 10 --call-site-limit 5
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 refresh-index
```

Batch files contain one command per line:

```text
symbol AppliedTools
references org.hack80.engine.movement.AppliedTools.buryAnObj
callers org.hack80.engine.level.LevelObjects.placeObject
diagnostics --errors-only --file java/src/org/hack80/engine/movement/AppliedTools.java
```

Run them with JSONL output:

```sh
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 batch /tmp/jdtls-agent-smoke.queries --jsonl
```

## JSON-RPC mode

For direct LLM/tool-loop use, keep the daemon warm and run a persistent stdin/stdout bridge:

```sh
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 rpc
```

For a one-shot JSON-RPC request without a live stdin session, pass the JSON object after `rpc`:

```sh
./scripts/jdtls-agent --project /Users/andrewpullin/personal/nethack/hack80 rpc '{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.status","params":{}}'
```

The bridge accepts one newline-delimited JSON-RPC object per line and prints one JSON-RPC response per request. Method names may be bare command names or prefixed with `jdtlsAgent.` / `jdtlsAgent/`.

Example request:

```json
{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.references","params":{"query":"org.hack80.engine.movement.AppliedTools.buryAnObj","limit":10}}
```

Example response shape:

```json
{"jsonrpc":"2.0","id":1,"result":{"ok":true,"command":"references","results":[...]}}
```

Named params use `query`, `symbol`, or `name` for the first command argument. Options can be supplied either directly in `params` (`limit`, `callSiteLimit`, `includeTests`, `includeDeclaration`, `errorsOnly`, `file`, `full`) or under `params.options`. Default `--limit` is 50; raise it deliberately for broad queries.

The daemon's loopback TCP protocol accepts the same JSON-RPC lines directly; the port is stored in `.jdtls-agent/run/<project-key>.port`.


## Commands

- `status` prints daemon state, project root, JDTLS command/PID, workspace data dir, last server status, source-file count, and query latency counters.
- `symbol <query>` searches the local declaration index and also probes JDTLS `workspace/symbol`. Human output is path-oriented; `--json` includes stable objects.
- `definition <symbol>` resolves a class, method, or field to exact `file:line:column`.
- `references <symbol>` resolves the declaration, then calls `textDocument/references`. Declarations are excluded unless `--include-declaration` is passed.
- `callers <symbol>` uses `textDocument/prepareCallHierarchy` and `callHierarchy/incomingCalls`; if JDTLS returns no hierarchy, it groups references by enclosing method.
- `callees <symbol>` uses `textDocument/prepareCallHierarchy` and `callHierarchy/outgoingCalls`.
- `diagnostics` returns currently published JDTLS diagnostics and reports whether diagnostics have actually been published for the requested scope. Use `--file <path>` to open/reconcile a file first.
- `field-writes <field-symbol>` uses semantic JDTLS references and AST context to report assignment, compound-assignment, and increment/decrement writes to a field.
- `api-surface <package-or-class>` lists public/static methods in a scope and groups semantic references by caller.
- `mutation-map <package-or-class>` lists fields owned by a scope that are written from outside that scope, with sampled write sites.
- `refresh-index` checks source fingerprints and rebuilds the local declaration index if files changed.
- `batch <file>` sends many commands through the warm daemon; `--jsonl` prints one response object per line.
- `rpc` keeps one client process connected to the daemon and translates newline-delimited JSON-RPC on stdin/stdout; `rpc '<json>'` sends one JSON-RPC request.

Common options:

- `--json`
- `--jsonl`
- `--limit N`
- `--call-site-limit N`
- `--include-tests` / `--exclude-tests`
- `--include-declaration`
- `--source-root java/src`
- `--test-source-root java/test`
- `--full`

## Symbol syntax

Fully qualified names are preferred:

```text
org.hack80.engine.movement.AppliedTools
org.hack80.engine.movement.AppliedTools.buryAnObj
org.hack80.engine.movement.AppliedTools.buryAnObj(Obj,boolean)
```

Unqualified fragments are allowed for discovery (`symbol buryAnObj`, `symbol AppliedTools`). If overloads or duplicate names make a query ambiguous, the command fails and prints candidates instead of guessing.

## Read-only default

This version does not mutate hack80 source. JDTLS `workspace/applyEdit` requests are rejected by the daemon. Future quality-of-life commands that modify code should be tested against a toy project, not active hack80 development sources, before any apply mode exists.

## Smoke check

`scripts/smoke-jsonrpc` starts the daemon if needed, runs a fixed JSON-RPC batch, and validates response shapes:

```sh
./scripts/smoke-jsonrpc /Users/andrewpullin/personal/nethack/hack80
```

## Known limitations

- JDTLS still requires an Eclipse workspace internally. The daemon hides it under `.jdtls-agent/workspaces/`, but the dependency is real.
- Hack80 is a plain Makefile Java project, so JDTLS creates an invisible project model from source roots. This works for the smoke queries, but project-model edge cases may appear.
- Diagnostics are push-based; `diagnostics --file <path>` is more reliable than project-wide diagnostics immediately after startup.
- Call hierarchy is JDTLS/JDT based. Dynamic dispatch, generated content, or unusual translated code can still need human interpretation.
- The local declaration index uses the JDK compiler parser for mapping symbol strings to source positions and for classifying field-write context; semantic answers come from JDTLS.
- `field-writes` and `mutation-map` are semantically scoped by JDTLS references, then syntactically classify write context; unusual reflection or generated code still needs review.
