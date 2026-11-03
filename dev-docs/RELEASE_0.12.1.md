# 0.12.1: transaction-safe result closing

Release date: 2026-11-03. Coordinates: `dev.brikk.duckdb:quack-jdbc:0.12.1`.
The annotated `v0.12.1` tag identifies the release commit. Publication follows
the normal tag-triggered CI / Maven Central / GitHub Release workflow.

## Regression evidence

Desired-behavior tests were added and run **before changing driver code**.
The runtime baseline is `eeb46fc` (0.12.1-SNAPSHOT following `v0.12.0` at
`9ef00f3`); the release worktree also includes `fc17ca7`, which adds only
GitHub funding configuration, not runtime changes.

- `QuackEarlyCloseTest`: initial 10 scripted v1/v3 cases, **8 failures**, zero
  errors; the two already-completed `more=false` controls passed. Failures show
  missing terminal/draining FETCHes and missing close-time error propagation.
- `TransactionIntegrationTest#earlyCloseDoesNotPoisonManualTransaction`: all
  **6/6** repetitions failed on the next `SELECT 42` with `Current transaction
  is aborted (please ROLLBACK)`, using the requested two-statement reproduction.
- After the fix, both regressions pass. Further coverage includes implicit
  re-execution, prepared statements, Statement.close/getMoreResults, multiple
  unread batches, another statement on the session, commit, late DML errors,
  no FETCH retry after failure, cancellation/heartbeat during close, and failed
  APPEND uploads. Existing lifecycle/deadline tests remain part of verification.

Raw logs, XML, reproduction probe and verification script are retained locally
in `/tmp/opencode/quack-0121-evidence/`. `red-unit.log` / `red-unit-reports/`
and `red-live-v2.log` / `red-live-reports/` are the pre-fix evidence;
`green-focused-final.log` records the final focused suite. A provisional full
run exposed the failed-upload drain hazard and was stopped; the final full
matrix is recorded separately, not represented by that provisional run.

## Close policy and compatibility

For an otherwise healthy, uncancelled stream, finish through terminal FETCH,
discarding unread batches synchronously in **both commit modes**. One-row
inline results with `more=true` usually cost one additional terminal round
trip; `more=false` costs none. New PREPARE/transaction commands and APPEND
finish any preceding stream before proceeding. There is no SQL replay,
automatic CANCEL, asynchronous drain race, or raised decoding limit.

Only one fetched batch is retained at a time; total close time/row count is
**not bounded**. Ordinary per-request timeouts/decode limits still apply.
Explicit cancellation can interrupt result closing without waiting behind
FETCH; heartbeats continue. Connection.close retains its independent bounded
DISCONNECT and does not drain results. Cancellation, server invalidation and
already-failed FETCHes retain their existing local-cleanup/no-retry behavior.

An APPEND whose SEND_DATA/encoding already failed must not fetch a producer
still waiting for client input. That path preserves local cleanup and surfaces
the original error. After any server error, rollback remains caller-managed;
after uncertain transport/decode failure, discard the connection rather than
replaying SQL. Closing FETCH errors surface as SQLException; concrete driver
close/getMoreResults signatures now declare it, matching the JDBC interfaces.

## Native bytes used

Linux x86-64, official release CLI and signed core extensions; unsigned loading
and unsafe crypto were disabled. Fixtures use isolated extension directories,
validate identities and now log SHA256 of the actual CLI and loaded extension
files, rather than merely recording version labels.

The red live test and green v3 tests use DuckDB **2.0.0**, core commit
`d54cbbe3df`, Quack **f96420ed46** and httpfs **77392a7207**:

```text
c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb  duckdb-2.0.0
9c893bd543ec2c5253d0c47d5614f90a1bfa1791c735dc6bbcea4e8bd3811a97  quack.duckdb_extension
77e6c429aa04f25da7962572f12b19113e0ce46080fa1365ad66017aadcdb68d  httpfs.duckdb_extension
```

The v1 matrix uses DuckDB **1.5.6**, core commit **069cc9f9b5**, signed Quack
**7e80f7f** and httpfs **4bc690d**:

```text
61238cfbe9dfeaad4bcfcd8a48f6f7123dae2e9603aaadb1f77a4217aeb8980c  duckdb-1.5.6
efd924d2a87b0cbcdda8a7418aa1daf33e0267388c87817d2e622b0a918c0800  quack.duckdb_extension
ff36e7d2561e21b958c243c6bd5f6a10cfc055a98ea76c0280b94728b57588e2  httpfs.duckdb_extension
```

## Verification

Full release-version oracle verification runs on Java 17.0.2 and 21.0.2 against
both signed server releases, using matched native JDBC 1.5.6.0 / 2.0.0.0.
The v1 profile uses `-Dquack.it.required=true`; the v3 profile uses the CI report
gate with expected runtime `v2.0.0` and Quack `f96420ed46`. Every production class
is checked for major version **61** (Java 17), including Java 21 builds.

Reproduction script: `/tmp/opencode/quack-0121-evidence/verify-matrix.sh`.

| Java runtime | Signed server / matched JDBC oracle | Tests | Failures | Errors | Skips |
| --- | --- | ---: | ---: | ---: | ---: |
| 17.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 750 | 0 | 0 | 3 |
| 17.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 750 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 750 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 750 | 0 | 0 | 3 |

All four integration gates passed **17/17 required classes, 55 total reports**.
No required integration test was skipped. The three known skips are the optional
external preview-artifact test and two IPv6/address-dependent transport tests.
Maven is 3.9.16; all production class major versions are 61 on both JDKs.
Complete XML, fixture identity/hash logs and summary JSON are in the four
`release-java{17,21}-v{1,2}/` evidence directories; corresponding `.log` and
`-gate.log` files record the Maven invocation and integration gate.

## Separate decode-budget triage

The 40-million-row BIGINT range refusal was reproduced independently, even at
`quack_target_batch_bytes=1048576`. This is a real eager-expansion/streaming
limitation: serialized batch bytes do not bound decoded SEQUENCE cardinality.
The 256 MiB safety check is justified; the `long[]` alone would exceed it.
The INTEGER variant passes; the catalog's string-decoder stack was not reproduced
by the exact BIGINT SQL. No speculative decoder change is included in 0.12.1.

Follow-up: [DECODE_BUDGET_FOLLOWUP.md](DECODE_BUDGET_FOLLOWUP.md), also filed in
the project backlog. GitHub issues are disabled on this repository. No changes
were made in ducklake-catalog, trino-ducklake, or doris-ducklake.
