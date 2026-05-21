# jdtls-agent Usage Notes

Current unresolved usage notes from live use against `hack80`. Delete items as they are fixed.

## Operating Guidance

- Use fully qualified symbols for edit decisions.
- Use unqualified symbols for discovery only.
- Treat `callers` and `callees` as high-quality guidance, not proof of reachability; command tables, registries, and dynamic dispatch still need human judgment.
- Treat `field-writes` and `mutation-map` as semantic references plus syntactic write classification. Reflection/generated code still needs review.
- Cross-check surprising results with IntelliJ MCP or `tools/java_semantic_index.py`.
- Keep broad queries bounded with `--limit`; default limit is 50. Use `--call-site-limit` to bound per-caller/per-field detail.

## Remaining Issues / Improvements

- Add an MCP adapter after the JSON-RPC command surface has more real usage.
