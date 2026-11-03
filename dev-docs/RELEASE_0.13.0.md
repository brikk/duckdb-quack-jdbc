# 0.13.0: compact primitive result vectors

Release date: 2026-11-03. Coordinates: `dev.brikk.duckdb:quack-jdbc:0.13.0`.
The owner requested the normal tag-driven release, Maven Central publication,
public artifact resolution and subsequent `0.13.1-SNAPSHOT` development version.
The immutable `v0.13.0` tag identifies the release commit.

## Source and version scope

Implementation commit `6ac36307fe5ea45275c1ba404e294e5b6e0fb74b` was fast-forwarded
and pushed to main before release preparation. It is based on
`04bfa047465813531dbfbff0647e570ff7b41899`, the post-0.12.1 development commit.
The feature branch is `quack-big-results`; preparation is on `release/0.13.0`.

**0.13.0 is a minor release, not 1.0.0.** During pre-1.0 development, the new
sealed-vector variants merit a minor version rather than a patch release.
This is not a declaration of stable 1.0 API compatibility.

## Behavior and compatibility

- INTEGER/BIGINT SEQUENCE vectors retain start, step and row count instead of
  expanding into row-sized arrays. Random access preserves previous INTEGER
  narrowing and two's-complement BIGINT arithmetic, including intermediate
  multiplication overflow. Bounds are checked before access.
- Primitive CONSTANT vectors retain one immutable boxed value (or null) plus
  row count. Supported types are BOOLEAN, TINYINT/SMALLINT/INTEGER/BIGINT,
  UTINYINT/USMALLINT/UINTEGER, FLOAT and DOUBLE. Wrapper types, signed zero, NaN,
  nulls and inline numeric-source conversions retain their previous behavior.
- Allocation budgets charge compact stored descriptors, not hypothetical expanded
  arrays. Metadata, source values and all other actual allocations still consume
  the existing cumulative per-message budget; default limits are unchanged.
  Flat vectors, object constants, dictionary projection arrays and temporal
  conversions retain their existing accounting and validation.
- Dictionary sources can now be compact, but projection remains eager and typed.
  APPEND null masks are preserved when compact constants are encoded as flat
  vectors. Outbound APPEND buffering is not made lazy or generally memory-bounded.
- JDBC values and metadata are unchanged. Low-level code assuming every numeric
  DecodedVector is IntVec/LongVec with array backing, or exhaustively switching
  over sealed variants, must allow IntSequenceVec, LongSequenceVec and ConstantVec.
  Java 17 bytecode remains the floor and no runtime dependency is added.

This fixes the independently reproduced 40-million-row compressed BIGINT range
refusal. It is **not** a general bound on arbitrary wide/nested/flat results,
application-retained data, process RSS or concurrent connections. It does not
change server batching, rewrite/replay SQL, or use CANCEL to avoid result draining.
0.12.1's synchronous transaction-safe close and independent cancellation,
heartbeat and bounded DISCONNECT behavior remain in effect.

The implementation and broader remaining work are tracked in
[BIG_RESULTS_TODO.md](BIG_RESULTS_TODO.md). No changes or substituted driver
dependencies were made in ducklake-catalog, trino-ducklake or doris-ducklake.

## Red-then-green and fixed-heap evidence

Evidence root: `/tmp/opencode/quack-big-results-evidence/`.

- `red-unit.log` / `red-unit-reports/`: six original desired-behavior tests fail
  before decoder changes, with two assertion failures and four budget errors.
- `red-live.log` / `red-live-reports/`: the original eager decoder exhausts the
  deliberately constrained 64 MiB child JVM on the signed-server BIGINT range.
  The normal per-message budget is larger than that heap.
- `red-nested-normalization.log` and `green-final-unit.log`: additional coverage
  catches and preserves numeric normalization for CONSTANT-over-SEQUENCE sources.
- Full 40-million-row BIGINT range and primitive constant reads pass at default
  decoder limits in a **64 MiB JVM**, followed by early close of another large
  range, a successful query in the same manual transaction, and rollback.
- Synthetic extreme-cardinality vectors fit a **32 MiB JVM**. Further tests cover
  exact Java wrappers, random access, nulls, empty vectors, row bounds, malformed
  input, nesting/budget boundaries, shared message budgets, typed dictionary
  projections and small APPEND round trips on both supported wire protocols.

The original full implementation matrix passes on Java 17.0.2/21.0.2 against
signed DuckDB 1.5.6/v1 and 2.0.0/v3: **762 tests each, zero failures/errors, three
known optional/environment skips**, with all required integration classes.
Release-version verification uses the same matrix and is recorded separately
in `/tmp/opencode/quack-0130-evidence/` (`verify-matrix.sh`, per-profile Maven and
gate logs, XML reports, fixture logs and JSON summaries).

### Final release-version verification

| Java | Signed server / matched native JDBC oracle | Tests | Failures | Errors | Skips |
| --- | --- | ---: | ---: | ---: | ---: |
| 17.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 762 | 0 | 0 | 3 |
| 17.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 762 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 762 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 762 | 0 | 0 | 3 |

Every gate passed **17/17 required integration classes, 56 reports**. No required
integration test was skipped. The three known skips are the optional external
preview pair and two IPv6/address-dependent transport tests. All production
classes are major version **61** on both JDKs. These runs use project version
**0.13.0**, not the earlier development snapshot.

The implementation's merged-main
[CI run](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/41574045095)
also succeeded before tagging the release.

## Native identities and exact bytes

Official Linux x86-64 release CLIs and signed core extensions are used in isolated
fixture directories. Unsigned extension loading and unsafe crypto are disabled.
Each fixture validates runtime/extension identities and logs actual file SHA256s.

DuckDB 1.5.6 (`069cc9f9b5`), Quack `7e80f7f`, httpfs `4bc690d`:

```text
61238cfbe9dfeaad4bcfcd8a48f6f7123dae2e9603aaadb1f77a4217aeb8980c  duckdb-1.5.6
efd924d2a87b0cbcdda8a7418aa1daf33e0267388c87817d2e622b0a918c0800  quack.duckdb_extension
ff36e7d2561e21b958c243c6bd5f6a10cfc055a98ea76c0280b94728b57588e2  httpfs.duckdb_extension
```

DuckDB 2.0.0 (`d54cbbe3df`), Quack `f96420ed46`, httpfs `77392a7207`:

```text
c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb  duckdb-2.0.0
9c893bd543ec2c5253d0c47d5614f90a1bfa1791c735dc6bbcea4e8bd3811a97  quack.duckdb_extension
77e6c429aa04f25da7962572f12b19113e0ce46080fa1365ad66017aadcdb68d  httpfs.duckdb_extension
```

The oracle profile uses native JDBC 1.5.6.0 / 2.0.0.0 respectively. Maven is
3.9.16. Required v1 fixtures use `-Dquack.it.required=true`; both profile gates
require all integration reports and pin the expected signed-server identities.
