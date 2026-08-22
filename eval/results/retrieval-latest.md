# Code retrieval evaluation

- Generated: `2026-08-22T15:54:36.876018100Z`
- Git commit: `8b7ac8280d488fad41a46de687733278fec6d78b`
- Runtime: Java `17.0.16` on `Windows 11 amd64`
- Dataset: `eval/retrieval/ground-truth.json`

| Configuration | Embedding | Queries | Recall@5 | MRR@10 | Avg latency |
|---|---|---:|---:|---:|---:|
|keyword-only|feature-hash:256|8|1.000|1.000|1.438 ms|
|feature-hash-hybrid|feature-hash:256|8|1.000|1.000|0.566 ms|

Ranks use exact human-labeled JavaParser symbols; rank `0` means absent from the top 10.
