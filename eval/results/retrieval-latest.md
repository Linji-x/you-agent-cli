# Code retrieval evaluation

- Generated: `2026-08-22T15:47:05.904546300Z`
- Git commit: `3a4d6b29d487e150588754f40f395b1713215e25`
- Runtime: Java `17.0.16` on `Windows 11 amd64`
- Dataset: `eval/retrieval/ground-truth.json`

| Configuration | Embedding | Queries | Recall@5 | MRR@10 | Avg latency |
|---|---|---:|---:|---:|---:|
|keyword-only|feature-hash:256|8|1.000|1.000|1.608 ms|
|feature-hash-hybrid|feature-hash:256|8|1.000|1.000|0.852 ms|

Ranks use exact human-labeled JavaParser symbols; rank `0` means absent from the top 10.
