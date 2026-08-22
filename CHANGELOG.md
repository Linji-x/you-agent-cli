# Changelog

All notable changes are documented here. The project follows semantic versioning after the initial clean-room release.

## [Unreleased]

## [0.2.0] — 2026-08-22

### Added

- MCP server configuration, environment placeholders, lifecycle manager, status command, and end-to-end registration tests.
- Agent tools for code indexing, hybrid search, symbol lookup, and relation lookup.
- Optional OpenAI-compatible Embedding and deterministic Feature Hash fallback.
- DAG dependency-output context, explicit `parallelSafe`, exclusive-resource guards, and bounded parallel execution.
- Labeled code-retrieval evaluation and credential-gated online Agent evaluation.
- JaCoCo, Java 21 compatibility CI, Dependabot configuration, and tag-based release workflow.
- `ROADMAP.md` and a product-first README.

### Changed

- Renamed `HashEmbeddingModel` to `FeatureHashEmbeddingModel` to accurately describe its lexical baseline behavior.
- Standardized the 25 fixed experiments as the deterministic offline conformance benchmark.
- Context accounting now includes Tool Call names, arguments, IDs, and results.
- Chinese long-term-memory retrieval now includes explainable character N-grams.

### Fixed

- Repeated-failure termination now counts only consecutive identical failures.
- JSON Schema validation now recursively checks nested objects and array items.
- Command output is bounded while being read, and timeouts terminate parent and descendant processes.
- stdio MCP timeouts now abort the transport so a reader task cannot remain permanently blocked.
- MCP protocol negotiation rejects unsupported versions.

## [0.1.0] — 2026-08-22

- Initial independent clean-room Java 17 implementation with ReAct, basic DAG execution, explicit Memory, Java code indexing, MCP protocol primitives, offline Demo, 25 fixed experiments, CI, and MIT licensing.

[Unreleased]: https://github.com/Linji-x/you-agent-cli/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Linji-x/you-agent-cli/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Linji-x/you-agent-cli/releases/tag/v0.1.0
