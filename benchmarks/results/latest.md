# Offline benchmark results

- Generated: `2026-08-22T12:21:12.896168200Z`
- Runtime: Java `17.0.16` on `Windows 11 amd64`
- Result: **25/25 passed**

| ID | Area | Result | Time | Evidence |
|---|---|---:|---:|---|
|R01|ReAct|PASS|93 ms|COMPLETED in 2 rounds; tool result present|
|R02|ReAct|PASS|3 ms|MAX_ROUNDS at round 2|
|R03|ReAct|PASS|7 ms|REPEATED_FAILURE after 3 identical failures|
|R04|ReAct|PASS|0 ms|COMPLETED in 1 round with no tools|
|R05|ReAct|PASS|1 ms|INVALID_ARGUMENTS fed back; next round COMPLETED|
|D01|DAG|PASS|3 ms|inspect -> generate -> verify|
|D02|DAG|PASS|1 ms|root=FAILED; child=BLOCKED|
|D03|DAG|PASS|0 ms|cycle rejected: cycle detected: a -> b -> a|
|D04|DAG|PASS|0 ms|supplemental fix task SUCCEEDED|
|D05|DAG|PASS|1 ms|left and right SUCCEEDED|
|M01|Memory|PASS|25 ms|1 fact visible after reopen|
|M02|Memory|PASS|15 ms|alpha sees global+alpha only|
|M03|Memory|PASS|23 ms|SQLite fact ranked first|
|M04|Memory|PASS|16 ms|fact deleted by id|
|M05|Memory|PASS|3 ms|511 -> 67 estimated tokens|
|S01|CodeSearch|PASS|366 ms|4 AST chunks indexed|
|S02|CodeSearch|PASS|20 ms|AccountService#loadAccount(String) ranked first|
|S03|CodeSearch|PASS|23 ms|AccountService CONTAINS method relation stored|
|S04|CodeSearch|PASS|25 ms|search succeeded after SQLite reopen|
|S05|CodeSearch|PASS|22 ms|AccountService symbol returned in top 3|
|T01|Tools|PASS|0 ms|POLICY_DENIED|
|T02|Tools|PASS|0 ms|INVALID_ARGUMENTS: missing path|
|T03|Tools|PASS|3 ms|round-trip-ok|
|P01|MCP|PASS|1 ms|initialize -> initialized -> READY -> CLOSED|
|P02|MCP|PASS|1 ms|mcp__demo__echo returned hello-mcp|
