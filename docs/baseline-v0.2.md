# v0.2 baseline audit

The feature branch started from `main` commit `9f648b42b0a8241ca65dd744ba1e60be272811b4` with a clean worktree.

## Reproducible baseline

Command on Java 17.0.16 / Windows 11 amd64:

```powershell
.\run.ps1 --verify
```

Observed before v0.2 changes:

- Maven build and shaded JAR succeeded.
- 16 JUnit tests passed with zero failures, errors, or skips.
- The offline Demo completed its real list/write/read flow.
- The 25 fixed offline experiments reported 25/25 PASS.

## Confirmed integration gaps

- MCP client/transports existed, but no config loader or CLI/Agent lifecycle manager loaded servers into the runtime.
- JavaParser/SQLite retrieval was available through `--index` and `--search`, but not registered as ReAct tools.
- DAG nodes executed sequentially and downstream workers did not receive dependency outputs.
- Repeated failures were counted across the entire run instead of consecutively.
- Tool Schema validation was shallow, and command output was fully buffered before truncation.
- No labeled retrieval metrics, online Agent evaluation harness, JaCoCo report, Java 21 CI, roadmap, or changelog existed.

This audit records evidence from the original clean-room `main`; it does not claim that future roadmap items were delivered.
