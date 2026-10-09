# Roadmap

Roadmap items describe intended work, not shipped behavior. The README and tests are authoritative for the current release.

## v0.2 — interview-ready runtime integration

- [x] Load `.you-agent/mcp.json`, negotiate MCP, register remote tools, expose status, and close transports.
- [x] Register JavaParser/SQLite retrieval as four ReAct tools.
- [x] Add optional OpenAI-compatible Embedding with an offline Feature Hash fallback.
- [x] Propagate direct dependency outputs and add conservative, resource-aware DAG parallelism.
- [x] Harden recursive schemas, failure tracking, context accounting, bounded command output, and process-tree timeout.
- [x] Add deterministic retrieval metrics and a credential-gated online Agent evaluation harness.
- [x] Add Java 17/21 CI, JaCoCo, dependency updates, secret scanning, and a tag release workflow.

## Next

### Runtime safety

- [ ] Optional container/VM execution backend with network and filesystem policies.
- [ ] Human approval policies for destructive file changes, commands, and external MCP tools.
- [ ] Durable turn snapshots and explicit rollback.

### MCP

- [ ] OAuth authorization flows and credential-store integration.
- [ ] Resources, prompts, sampling, pagination, and progress notifications.
- [ ] Supervised server restart, session recovery, and health backoff.

### Retrieval

- [ ] Incremental file hashing instead of full index rebuilds.
- [ ] Multi-language chunkers and repository-scale approximate vector search.
- [ ] Larger public labeled datasets and model-based embedding comparisons.

### Evaluation and UX

- [ ] Expand the online suite across multiple providers and publish only real, reproducible runs.
- [ ] Cost/usage accounting from provider usage fields in addition to local estimates.
- [ ] Rich terminal rendering while preserving a plain, scriptable mode.
