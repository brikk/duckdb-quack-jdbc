# Large-result memory work

Branch/worktree: `quack-big-results`, based on `0.12.2-SNAPSHOT` after 0.12.1.

## Current scope: compact primitive vectors

- [x] Confirm desired-behavior unit and signed-server regressions fail before changing the decoder.
- [x] Keep INTEGER/BIGINT SEQUENCE vectors as start/step/count instead of row-sized arrays.
- [x] Keep primitive BOOLEAN/numeric CONSTANT vectors as one immutable value plus row count, including nulls.
- [x] Charge compact storage rather than hypothetical expanded arrays; retain existing limits for all actual allocations and non-lazy encodings.
- [x] Preserve JDBC types/getters, bounds, existing integer arithmetic semantics, nesting validation, dictionary projections and small APPEND round trips.
- [x] Demonstrate the 40-million-row reproduction with default limits in a fixed-heap JVM; verify early close still finishes the transaction safely.
- [x] Run the signed v1/v3 matched-oracle suites on Java 17 and 21; retain Java 17 bytecode and record results.
- [x] Document scope, representation compatibility, evidence and remaining limits.

## Follow-ups (not implemented in this branch's first change)

- [ ] Server-side row ceilings plus byte targets for PREPARE inline results and FETCH; define an explicit oversized-value policy.
- [ ] Lazy DICTIONARY projections, retaining bounds and accounting for the selection array and dictionary source.
- [ ] Evaluate immutable object constants separately (strings/decimals/etc.); do not implicitly share mutable BLOB/nested values.
- [ ] Decode one chunk at a time rather than materializing every chunk in a response; explicitly design cumulative-allocation versus retained-memory budgets.
- [ ] Validate/discard unread encoded values during close without materializing them; still await terminal completion and surface server errors.
- [ ] Bound prefetch/backpressure and aggregate connection memory; never queue unbounded background FETCHes.
- [ ] Evaluate bounded/compact outbound APPEND encoding separately; the current encoder still writes flat payloads and is not a zero-copy large-result transfer.
- [ ] Investigate disk spill only if retained/scrollable results need it, including quotas, cleanup and confidentiality.
- [ ] Reconcile the catalog's string-decoder failure stack with its exact SQL/wire types; this branch targets the independently reproduced BIGINT SEQUENCE expansion.

Do not raise default limits, replay/rewrite SQL into pagination, use CANCEL as a
normal-close remedy, change the server implicitly, or modify downstream projects.
The original diagnosis is in [DECODE_BUDGET_FOLLOWUP.md](DECODE_BUDGET_FOLLOWUP.md).

## Implemented design

`DecodedVector.IntSequenceVec` / `LongSequenceVec` retain type, row count, start
and increment. Values are calculated only when accessed. INTEGER narrowing and
two's-complement BIGINT arithmetic match the previous eager loops, including
cases where an intermediate multiplication overflows but the final value is
correct. Row bounds are checked before arithmetic; no late type conversion or
new overflow exception is introduced. Temporal sequences keep their existing
eager validation, scalar accounting and checked arithmetic.

`DecodedVector.ConstantVec` accepts only the existing immutable primitive wrapper
types for BOOLEAN, TINYINT/SMALLINT/INTEGER/BIGINT, UTINYINT/USMALLINT/UINTEGER,
FLOAT and DOUBLE. Null constants use no row-sized validity mask until explicitly
encoded for APPEND. Empty vectors, signed zero, NaN, nulls and Java wrapper types
are preserved. UBIGINT, HUGEINT/UHUGEINT, temporal/decimal/string/BLOB/nested
constants remain on their previous eager paths.

The decoder reserves a constant descriptor allowance for these encodings instead
of a hypothetical expanded per-row array. Source values, metadata and every
other actual allocation remain budgeted. No limit default is raised. Dictionaries
still pay for their selection/projection arrays, even when their source is now a
compact sequence/constant. Their primitive projections remain primitive arrays.
Flat decoding is unchanged; message budgets remain cumulative and shared across
columns/chunks, and inline nesting limits are unchanged.

JDBC values and metadata are unchanged. Low-level callers must use DecodedVector
accessors instead of assuming every numeric vector has an `int[]`/`long[]` backing
array. The sealed interface gains three variants; concrete-class assumptions
(including exhaustive switches in downstream code) may need updating. Outbound
APPEND still expands into flat wire payloads; this is a decoding optimization,
not a general bound on encoding, application-retained rows, process RSS or
aggregate concurrent connections. No SQL rewrite/replay, automatic server
setting change, or CANCEL remedy is involved.

## Evidence

Local evidence root: `/tmp/opencode/quack-big-results-evidence/`.

- `red-unit.log` / `red-unit-reports/`: the original six desired-behavior tests
  fail before decoder changes (two assertion failures, four budget errors).
- `red-live.log` / `red-live-reports/`: the first 40-million-row BIGINT query
  exhausts a fixed 64 MiB child JVM heap on the original eager decoder. The
  default per-message budget is larger than this deliberately constrained heap.
- `green-focused.log`: the initial unit/live cases pass after compact decoding.
- `green-codec-live.log`: expanded unit, budget, temporal, malformed-input,
  streaming and live APPEND coverage passes, including full 40-million-row
  range/constant reads, safe early close and subsequent transaction reuse in
  the 64 MiB child. Synthetic extreme-cardinality vectors also fit a 32 MiB JVM.
- `red-nested-normalization.log` / `green-final-unit.log`: an additional regression
  confirms that CONSTANT-over-SEQUENCE numeric sources preserve the old narrowing
  and Java wrapper types. The final matrix was rerun after that fix; preliminary
  761-test results are not the final verification.
- `verify-matrix.sh`: full signed-server, matched-oracle verification on Java
  17.0.2 and 21.0.2 with Java 17 class-major checks. Results are recorded separately
  under `java{17,21}-v{1,2}/` with Maven/gate logs beside them.

The live v3 tests use official DuckDB 2.0.0 (`d54cbbe3df`), signed core Quack
`f96420ed46`, httpfs `77392a7207`, with unsigned loading and unsafe crypto off.
The v1 matrix uses released DuckDB 1.5.6 (`069cc9f9b5`), Quack `7e80f7f`, httpfs
`4bc690d`. Each fixture logs the actual CLI/extension SHA256s; the released bytes
match the identities in [RELEASE_0.12.1.md](RELEASE_0.12.1.md).

### Final verification (2026-11-03)

| Java | Signed server / matched native JDBC oracle | Tests | Failures | Errors | Skips |
| --- | --- | ---: | ---: | ---: | ---: |
| 17.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 762 | 0 | 0 | 3 |
| 17.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 762 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 1.5.6 / JDBC 1.5.6.0 | 762 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 2.0.0 / JDBC 2.0.0.0 | 762 | 0 | 0 | 3 |

All four gates pass **17/17 required integration classes, 56 total reports**.
No required integration test is skipped. The three skips are the optional
external preview pair and two IPv6/address-dependent transport tests. Every
production class is major version **61** (Java 17), including the Java 21 builds.
Maven is 3.9.16. All fixture CLI/extension hashes are consistent across the two
JDKs and match the signed release bytes linked above. These results qualify the
implementation, not downstream dependency adoption. Release preparation and
publication are recorded separately in [RELEASE_0.13.0.md](RELEASE_0.13.0.md).
