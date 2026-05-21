# jdtls-agent

`jdtls-agent` is a persistent semantic navigation bridge for Java codebases.

It exists for coding agents and other command-line workflows that need IDE-grade answers without opening an IDE, booting a full editor integration, or starting `jdtls` on every query. The tool keeps one warm JDT Language Server process per project, then exposes cheap CLI and JSON-RPC entry points for:

- exact symbol lookup
- exact definitions
- exact semantic references
- callers / callees
- diagnostics
- field write classification
- API surface summaries
- mutation maps
- batch queries

The first target was Hack80, a large Java port/refactor where grep-heavy archaeology was too slow and too error-prone for agent-driven cleanup.

## Why this exists

Most useful Java LSP operations are position-based. Agents usually know names, not file/line coordinates.

`jdtls-agent` fills that gap by combining:

1. a warm `jdtls` daemon for semantic answers
2. a lightweight local source index for name-to-position resolution
3. CLI and JSON-RPC entry points that are stable enough for automation

That lets an agent ask:

- "who calls this method?"
- "is this field written outside its owning package?"
- "what public/static methods are actually used?"
- "did the source tree change enough that I should refresh my symbol index?"

without paying JDTLS startup cost on every request.

## What this repo does not do

- It does **not** fork JDTLS.
- It does **not** vendor JDTLS as a git submodule.
- It does **not** patch Eclipse internals.
- It does **not** mutate project source by default.

JDTLS is a runtime dependency. `jdtls-agent` launches the installed `jdtls` binary and isolates Eclipse state under a project-local `.jdtls-agent/` directory.

## Repository contents

- `src/main/java/` — daemon, client, protocol, source index, query logic
- `src/test/java/` — unit tests for protocol and source-index behavior
- `scripts/jdtls-agent` — CLI launcher
- `scripts/jdtls-agentd` — daemon launcher with cleanup watchdog
- `scripts/smoke-jsonrpc` — end-to-end smoke test
- `DESIGN.md` — architecture notes
- `skill/jdtls-agent/SKILL.md` — reusable agent-facing operating guidance

## Requirements

### Build

The jar targets Java 17 bytecode.

Any JDK that can run Maven and emit Java 17-compatible bytecode is fine. On the current machine, the default JDK 20 works.

### Runtime

`jdtls` itself requires Java 21.

The launcher scripts prefer Homebrew OpenJDK 21 automatically when present:

- `/opt/homebrew/opt/openjdk@21/bin/java`

`jdtls` discovery order:

1. `--jdtls /path/to/jdtls`
2. `JDTLS_AGENT_JDTLS=/path/to/jdtls`
3. `/opt/homebrew/bin/jdtls`
4. `jdtls` from `PATH`

### Suggested local install on macOS/Homebrew

```sh
brew install openjdk@21 jdtls
```

## Build

```sh
cd jdtls-agent
mvn -q test
mvn -q -DskipTests package
```

The launch scripts run:

```text
target/jdtls-agent-0.1.0-all.jar
```

## Quick start

Start one daemon per project:

```sh
./scripts/jdtls-agentd --project /absolute/path/to/project
```

Then query it:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project status
./scripts/jdtls-agent --project /absolute/path/to/project symbol AppliedTools --limit 10
./scripts/jdtls-agent --project /absolute/path/to/project definition org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /absolute/path/to/project references org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /absolute/path/to/project callers org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /absolute/path/to/project callees org.hack80.engine.movement.AppliedTools.buryAnObj
./scripts/jdtls-agent --project /absolute/path/to/project diagnostics --errors-only --file java/src/org/hack80/engine/movement/AppliedTools.java
./scripts/jdtls-agent --project /absolute/path/to/project field-writes org.hack80.engine.Obj.where --limit 10
./scripts/jdtls-agent --project /absolute/path/to/project api-surface org.hack80.engine.movement.AppliedTools --limit 10
./scripts/jdtls-agent --project /absolute/path/to/project mutation-map org.hack80.engine --limit 10 --call-site-limit 5
./scripts/jdtls-agent --project /absolute/path/to/project refresh-index
```

## Agent-first JSON-RPC mode

For tool loops, the most useful interface is the persistent stdin/stdout bridge:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project rpc
```

Send newline-delimited JSON-RPC 2.0 requests:

```json
{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.references","params":{"query":"org.hack80.engine.movement.AppliedTools.buryAnObj","limit":10}}
{"jsonrpc":"2.0","id":2,"method":"jdtlsAgent.mutation-map","params":{"query":"org.hack80.engine","limit":5,"callSiteLimit":3}}
```

For one-shot RPC from shell automation:

```sh
./scripts/jdtls-agent --project /absolute/path/to/project rpc '{"jsonrpc":"2.0","id":1,"method":"jdtlsAgent.status","params":{}}'
```

The daemon's loopback TCP socket accepts the same newline-delimited JSON-RPC objects directly. The active port is stored at:

```text
.jdtls-agent/run/<project-key>.port
```

## Commands

### Read-only semantic queries

- `status`
  - daemon state
  - project path
  - JDTLS command / pid
  - workspace path
  - source-file count
  - query counters / average latency

- `symbol <query>`
  - local declaration-index results
  - optional JDTLS `workspace/symbol` probe

- `definition <symbol>`
  - exact declaration location

- `references <symbol>`
  - semantic JDTLS references
  - `--include-declaration` optional

- `callers <symbol>`
  - JDTLS incoming call hierarchy
  - falls back to references grouped by enclosing method if necessary

- `callees <symbol>`
  - JDTLS outgoing call hierarchy

- `diagnostics`
  - published JDTLS diagnostics
  - distinguishes "not published yet" from "published but empty"

### Hybrid semantic/source-index queries

- `field-writes <field-symbol>`
  - exact JDTLS field references
  - local AST classification of assignment / compound assignment / increment / decrement write context

- `api-surface <package-or-class>`
  - public/static methods in scope
  - grouped caller summaries

- `mutation-map <package-or-class>`
  - fields owned by a scope
  - writes coming from outside that scope
  - bounded per-field write-site detail via `--call-site-limit`

- `refresh-index`
  - explicit source-index refresh result
  - useful after code generation or large file rewrites

### Batch / transport helpers

- `batch <file>`
  - one command per line
  - executes through one warm daemon session
  - `--jsonl` prints one result object per line

- `rpc [json-request]`
  - persistent JSON-RPC bridge
  - or single one-shot JSON-RPC request when a payload is supplied inline

## Common options

- `--project <path>`
- `--json`
- `--jsonl`
- `--limit N`
- `--call-site-limit N`
- `--include-tests`
- `--exclude-tests`
- `--include-declaration`
- `--errors-only`
- `--file <path>`
- `--source-root <path>`
- `--test-source-root <path>`
- `--full`

## Symbol syntax

Fully qualified names are the reliable path for automation:

```text
org.hack80.engine.movement.AppliedTools
org.hack80.engine.movement.AppliedTools.buryAnObj
org.hack80.engine.movement.AppliedTools.buryAnObj(Obj,boolean)
```

Unqualified fragments are fine for discovery:

```text
symbol AppliedTools
symbol buryAnObj
```

If overloads or duplicate names make a query ambiguous, the command fails and prints candidates instead of guessing.

## How it works

### 1. Warm daemon

`jdtls-agentd` starts one `jdtls` child process and keeps it warm.

### 2. Isolated Eclipse state

All workspace/config/cache/home state lives under:

```text
.jdtls-agent/
```

That keeps the target repository clean and avoids leaking state into `~/.eclipse`.

### 3. Local name resolver

The source index walks configured source roots and records:

- class names
- field names
- method names
- parameter lists
- modifiers
- declaration locations

That is how the tool turns `org.foo.Bar.baz` into a `textDocument/position` request for JDTLS.

### 4. Fingerprint-based refresh

The source index keeps file fingerprints in memory. Each request checks whether the source set changed and rebuilds only when necessary.

### 5. Read-only by default

If JDTLS attempts `workspace/applyEdit`, the daemon rejects it. This tool is for navigation and analysis first.

## Smoke test

End-to-end validation:

```sh
./scripts/smoke-jsonrpc /absolute/path/to/project
```

The smoke script starts the daemon if needed, runs a fixed JSON-RPC batch, and validates result shapes.

## Troubleshooting

### `jdtls-agentd --help`

This should print usage and exit. If it does not, the wrapper or daemon parse path regressed.

### No diagnostics yet

JDTLS publishes diagnostics asynchronously. A first diagnostics request may correctly report that nothing has been published yet.

### Diagnostics look wrong on plain Makefile projects

Treat diagnostics as advisory if the project model is weak. For Hack80 specifically, keep `make`/javac or IntelliJ as compile authority when JDTLS reports surprising unresolved symbols.

### Daemon or harness died and left stale state

Force cleanup with:

```sh
java -jar target/jdtls-agent-0.1.0-all.jar cleanup --project /absolute/path/to/project
```

## Known limitations

- JDTLS still carries Eclipse workspace assumptions internally.
- Plain source-root projects are less robust than first-class Maven/Gradle/Eclipse projects.
- Call hierarchy is useful guidance, not proof of total reachability.
- Dynamic dispatch, registries, and generated code still need human judgment.
- `field-writes` and `mutation-map` classify write context syntactically after semantic reference discovery; reflection and generated code remain edge cases.

## For agent authors

The repo ships a reusable skill file at:

```text
skill/jdtls-agent/SKILL.md
```

Use it when you want another agent or harness to adopt consistent operating rules for this tool.
