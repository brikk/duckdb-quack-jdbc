# 0.13.1: no replay after an ambiguous closed channel

Release date: 2026-11-06. Coordinates: `dev.brikk.duckdb:quack-jdbc:0.13.1`.
The owner requested the narrow client fix and normal verified release; the
immutable `v0.13.1` tag identifies the release commit. Preparation starts from
main `d8bfd7432c5f1503bd3b574ab2a1fbdbcee75661` (0.13.1-SNAPSHOT after 0.13.0).

## Fix and compatibility

`QuackHttpTransport.send` previously grouped ClosedChannelException with genuine
connection-establishment failures. For plain HTTP endpoints with several resolved
addresses, it could retry the identical POST at another address even if the first
write was accepted or FETCH advanced before the response was lost.

The production change removes **only ClosedChannelException** from that fallback
catch clause (and removes its now-unused import). It follows the ordinary
IOException path, retaining the original exception as cause. ConnectException
and HttpConnectTimeoutException still permit address fallback because they
indicate connection-establishment failure. HTTPS retains its original hostname
and single candidate for TLS verification.

An uncertain operation now fails instead of potentially succeeding through a
duplicate execution. Do not automatically replay uncertain writes after the
failure. Existing response-body failure cleanup, decode limits, protocol versions,
cancellation, heartbeat behavior and independent two-second CANCEL/DISCONNECT
budgets are unchanged. The Java 17 bytecode floor and runtime dependencies are
unchanged. No SQL rewrite, reconnect, or CANCEL workaround is introduced.

The same narrow classification fix appears in the unmerged original Gizmo JDBC
[PR #8](https://github.com/gizmodata/quack-jdbc/pull/8). Its other protocol,
result-close and cancellation changes are **not** imported.

## Red-then-green evidence

`QuackHttpTransportReplayTest` uses child JVMs with their own two-address hosts
file. The addresses are both loopback IPv4; no external DNS, IPv6 capability,
real HTTP connection or actual SQL execution is needed. It is never skipped on
single-address localhost environments, and does not alter the parent JVM's DNS.

Before changing production code, all **22** cases ran: **7 failures**, zero errors,
zero skips. The failing cases reported:

```text
Uncertain request replayed: sends=2, simulated accepts=2
```

The seven failures cover v1/v3 PREPARE and FETCH, v1 APPEND, v3 SEND_DATA and
heartbeat. The 15 controls already passed: real connect-failure classification,
single-attempt general/body I/O failures, and CANCEL/DISCONNECT behavior. The
stub simulates an accepted operation before losing its response; these are
deterministic retry-path tests, not a claim of live duplicate SQL execution.

After the fix, all 22 cases pass and assert original cause identity, send/accept
counts, distinct fallback addresses, response-body closure, and unchanged request
timeout selection. ConnectException and HttpConnectTimeoutException still try
the second address for ordinary requests; controls remain single-attempt.

Evidence root: `/tmp/opencode/quack-0131-evidence/`:

- `red-replay.log`, `red-reports/`: confirmed pre-fix failures.
- `green-focused.log`: the first focused run passed all 22 new cases but was
  interrupted during the existing disconnect suite; it is not represented as
  completed verification.
- `green-focused-resumed.log`: the resumed targeted transport/lifecycle run.
- `verify-matrix.sh`, `java{17,21}-v{1,2}.log`, matching `-gate.log` files and
  per-profile directories: release-version oracle verification, XML, JSON
  summaries, and fixture identity/hash logs.

The earlier independent public-0.13.0 audit is in
`/tmp/opencode/quack-upstream-review-2026-11-05/`; the committed regression now
provides a reproducible replacement for that temporary probe.

## Native verification scope

The normal full oracle matrix uses Java 17.0.2 and 21.0.2, Maven 3.9.16, and
released signed core servers in isolated fixture directories:

- DuckDB **1.5.6** (`069cc9f9b5`), Quack **7e80f7f**, httpfs **4bc690d**,
  native JDBC oracle **1.5.6.0**.
- DuckDB **2.0.0** (`d54cbbe3df`), Quack **f96420ed46**, httpfs **77392a7207**,
  native JDBC oracle **2.0.0.0**.

Unsigned loading and unsafe crypto are disabled. Fixture logs record SHA256 of
the actual CLI and both loaded extensions; the same released bytes are documented
in [RELEASE_0.13.0.md](RELEASE_0.13.0.md). The temporary 2.0.0 CLI had been cleaned
up, so it was restored from the official release archive and its SHA256 checked
against the previously verified `c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb`.
This is not a server-version upgrade.

### Final release-version verification

| Java | Signed server / matched native JDBC oracle | Tests | Failures | Errors | Skips |
| --- | --- | ---: | ---: | ---: | ---: |
| 17.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 784 | 0 | 0 | 3 |
| 17.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 784 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 784 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 784 | 0 | 0 | 3 |

Every gate passed **17/17 required integration classes, 57 total reports**.
No required integration test was skipped. The three known skips are the optional
external preview pair and two IPv6/address-dependent transport tests. Every
production class is major version **61** on both JDKs. All four runs use project
version **0.13.1**, not a development snapshot.

## Explicitly excluded upstream work

**Owner policy: ignore server-side proposals until they reach an actual binary
release.** No speculative server fixes, timeout settings, wire-schema changes,
new server builds, row-limit negotiation or server-side query-tag/auth features
are part of this release. Existing server pins and fixtures are unchanged.
No downstream catalog/Trino/Doris repository is modified or requalified here.
