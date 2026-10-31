# DuckDB Release Compatibility

## 1.5.6 Pre-Release Check (2026-09-05)

**No Quack protocol migration is indicated for the current 1.5.6 release
line.** This is a check of a development snapshot, not certification of the
final 1.5.6 release. Do not infer patch-release compatibility from
`duckdb/duckdb-quack`'s `main` branch: the 1.5 line has its own branch and
DuckDB pins the extension to a specific commit.

### Sources Checked

- DuckDB `v1.5-variegata` at
  [`2f10cfb426860f46a8ed59e8d6eb70958c3022b7`](https://github.com/duckdb/duckdb/commit/2f10cfb426860f46a8ed59e8d6eb70958c3022b7).
- Both [DuckDB 1.5.5's Quack pin](https://github.com/duckdb/duckdb/blob/v1.5.5/.github/config/extensions/quack.cmake)
  and [the checked release-branch pin](https://github.com/duckdb/duckdb/blob/2f10cfb426860f46a8ed59e8d6eb70958c3022b7/.github/config/extensions/quack.cmake)
  are `c1548111c1bfd16207e22fd3cb7e4bde1335b9d0`.
- At that Quack revision, [QUACK_VERSION is 1](https://github.com/duckdb/duckdb-quack/blob/c1548111c1bfd16207e22fd3cb7e4bde1335b9d0/src/include/quack_server.hpp),
  and the message schema still uses the existing handshake, FETCH and
  APPEND_REQUEST. The v3 heartbeat and streaming-send-data changes on
  Quack `main` are not in this pin.
- Quack's own `v1.5-variegata` tip (`7e80f7ffcc98d0b3e81d0e1df8cc1c2da240a64b`)
  is slightly newer than the pin. Its additional changes concern sourcing
  server tokens from secrets and canonical/IPv6 secret lookup, not wire
  messages. Even that branch tip should not be substituted for the pin.
- DuckDB core's binary serializer/deserializer implementations,
  DataChunk/Vector serialization implementations, and logical-type
  serialization schema are unchanged between 1.5.5 and the checked head.
  Serializer header changes add an Identifier wrapper using existing string
  encoding; DataChunk header changes add API aliases. Query execution
  backports can still change results, so source comparison is not sufficient
  on its own.

### Runtime Verification

Official artifacts from [DuckDB CI run 33934594168](https://github.com/duckdb/duckdb/actions/runs/33934594168):

- `duckdb-binaries-linux-amd64`: CLI reports `v1.5.6-dev146`, `2f10cfb426`.
- `main-extensions-v1.5-variegata-extension-linux_amd64`: the loaded Quack
  extension reports `c154811` in `duckdb_extensions()`.
- Driver tested: `74a72d3` (`0.7.0-SNAPSHOT`). Its `src/main` tree is identical
  to released `v0.6.0`; no driver changes were needed for these tests.
- Platform: Linux x86-64, Java 21.0.2, Maven 3.9.16 (Java 17 compilation target).

| Profile | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `clean verify` | 156 | 0 | 0 | 1 |
| `-Poracle verify` | 159 | 0 | 0 | 1 |

All integration suites ran, including in-memory CRUD, duplicate-key errors,
DML result-drain termination, transactions, multi-batch fetching, nested
types, and scalar/nested APPEND. The only skip was the IPv6 loopback transport
test. Oracle parity used **released duckdb_jdbc 1.5.5.0**, not an unreleased
1.5.6 JDBC artifact.

The CI extension is unsigned. Testing used a temporary launcher with
`-unsigned` and an isolated `extension_directory`, with the matching local
Quack artifact installed there. Normal fixture behavior (`INSTALL quack;
LOAD quack;`), release pins, and production signature verification were not
changed. Local artifacts and the launcher are under
`/tmp/opencode/quack-156/`; while present, rerun with:

```bash
QUACK_IT_DUCKDB=/tmp/opencode/quack-156/duckdb-test mise exec -- mvn --batch-mode --no-transfer-progress clean verify
QUACK_IT_DUCKDB=/tmp/opencode/quack-156/duckdb-test mise exec -- mvn --batch-mode --no-transfer-progress -Poracle verify
```

The upstream run built and uploaded these artifacts successfully but later
failed `concurrent_checkpoint_deletes_insert.test_slow` with a WAL replay
tuple-deletion conflict. This is a separate core-engine issue to watch;
passing our tests does not make this snapshot production-ready.

### Release-Day Checklist

1. Recheck the final `v1.5.6` tag's Quack pin and changes since the checked
   DuckDB head. If the extension pin moves, compare its protocol separately.
2. Test the final CLI with its signed core extension, verifying `version()`
   and `extension_version` from `duckdb_extensions()` rather than assuming
   which extension an existing cache supplies. Do not use `-unsigned` for
   this release verification.
3. Run both full profiles using `QUACK_IT_DUCKDB` to select the final CLI.
   Require zero skipped integration tests. Use
   `-Dduckdb.jdbc.version=1.5.6.0` for the oracle run once that artifact is
   available; otherwise clearly label the older oracle version.
4. Only after those checks pass, bump CI, `mise.toml`, and the oracle pin to
   the released versions. Keep the current 1.5.5 pins until then.

Do not merely raise the driver's advertised protocol range to 3: that would
claim support for incompatible messages. Protocol v3 remains separate work,
not a demonstrated requirement for 1.5.6.

## 1.5.6 Final Release Verification (2026-10-09)

The [released `v1.5.6` tag](https://github.com/duckdb/duckdb/releases/tag/v1.5.6)
is `069cc9f9b5`. Its
[Quack pin](https://github.com/duckdb/duckdb/blob/v1.5.6/.github/config/extensions/quack.cmake)
**changed** from the development snapshot checked above to
`7e80f7ffcc98d0b3e81d0e1df8cc1c2da240a64b`. That pin still declares
`QUACK_VERSION = 1` and uses v1 PREPARE, FETCH, and APPEND messages; it is
not the v3 extension from DuckDB 2.0. Do not substitute the earlier
`c154811` revision for the released extension.

The official 1.5.6 Linux x86-64 CLI and its **signed core** extensions were
installed and loaded in an isolated directory without `-unsigned`. The CLI
reports `v1.5.6 (Variegata) 069cc9f9b5`; `duckdb_extensions()` reports
Quack `7e80f7f` and httpfs `4bc690d`, both loaded from `core` with
`install_mode=REPOSITORY`; unsigned extensions and unsafe crypto are disabled.
The current `feature/quack-v1-v3-negotiation` driver was exercised with Java
21.0.2 / Maven 3.9.16 (compiled for Java 17):

| Profile | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `-Dquack.it.required=true clean verify` | 652 | 0 | 0 | 3 |
| `-Poracle -Dquack.it.required=true clean verify` | 658 | 0 | 0 | 3 |

The oracle profile uses **released `duckdb_jdbc` 1.5.6.0**. The three skips
are the optional externally supplied v3 preview test and two IPv6-dependent
transport tests; **no required integration test was skipped**. The CI
integration-report gate accepts 14/14 required classes and 45 reports on the
oracle profile. The oracle profile and report gate also passed under Java
17.0.2, matching CI's Java major version. To reproduce with the final CLI:

```bash
QUACK_IT_DUCKDB=/path/to/duckdb-1.5.6 mvn --batch-mode --no-transfer-progress -Dquack.it.required=true clean verify
QUACK_IT_DUCKDB=/path/to/duckdb-1.5.6 mvn --batch-mode --no-transfer-progress -Poracle -Dquack.it.required=true clean verify
```

The CI workflow, `mise.toml`, the required fixture's CLI/Quack identity, and
the JDBC oracle property now pin these released versions. This verifies the
1.5.6 release-day checklist above; it does not certify the final 2.0 release.

## DuckDB 2.0 Preview Validation (2026-10-08)

This is separate from the 1.5.6 assessment above. On the
`feature/quack-v1-v3-negotiation` branch, the driver negotiates v1 and v3 and
implements the version-dependent messages rather than merely advertising v3.

- CLI: [staged 2.0 alpha](https://artifacts.duckdb.org/staged/v2.0-cyanoptera/duckdb-cli-linux-amd64.tar.gz),
  `v2.0.0-alpha45672`, commit `f2f9329721` (the installer resolves this from
  `https://duckdb-staging.duckdb.org/latest_alpha_version.txt`). The older
  non-staged preview artifact URL is no longer the current download path.
- `INSTALL httpfs FROM core; LOAD httpfs` and `INSTALL quack FROM core; LOAD quack`
  succeed in an isolated extension directory **without `-unsigned`**. The
  loaded versions are `5e34903685` and `974927a394`, respectively, with
  `install_mode=REPOSITORY`, `installed_from=core`. DuckDB's
  [2.0 Quack pin](https://github.com/duckdb/duckdb/blob/f2f9329721/.github/config/extensions/quack.cmake)
  matches `974927a394b188755284682b73398ed50e86316c`.
- After adapting v3 tuple decoding, nested APPEND, inline responses, and
  version-specific test settings, `QUACK_IT_DUCKDB=/tmp/opencode/quack-v2-oct8/duckdb
  mvn --batch-mode --no-transfer-progress verify` passes: **656 tests,
  0 failures, 0 errors, 3 skips**. The skips are the optional test requiring
  a manually supplied unsigned CLI/extension pair and two IPv6-dependent
  transport tests. The full suite includes signed-server JDBC CRUD, FETCH,
  transactions, nested types, and APPEND, but is not 2.0 final certification.
- As a regression check for simultaneous v1 support, the same `verify` command
  with `QUACK_IT_DUCKDB=/tmp/opencode/quack-v1-155/duckdb` passes **656 tests,
  0 failures, 0 errors, 3 skips** against DuckDB 1.5.5 and signed core Quack.
  The `-Poracle verify` profile with released `duckdb_jdbc` 1.5.5.0 passes
  **658 tests, 0 failures, 0 errors, 3 skips** on that v1 server.
  These paths are local test artifacts, not release dependencies.

The v3 server has different batch-size settings, and DuckDB 2.0's parser
rejects one v1-only multiline escaped-string continuation. The integration
tests select the matching settings and do not send that invalid SQL to v3;
this is not a claim that the two SQL parsers accept exactly the same syntax.

Before claiming final DuckDB 2.0 support, recheck the final 2.0 tag's Quack
pin, run full verification with the matching signed extension, and run the
oracle profile with a 2.0 `duckdb_jdbc` artifact. Do not bump the 1.5.x CI,
`mise.toml`, or oracle pins on the strength of a 2.0 alpha test alone. The
1.5.6 release-day checklist was subsequently completed against the final
signed 1.5.6 artifacts, separately from this 2.0 check.

## DuckDB 2.0 Preview Recheck (2026-10-16)

The [staged CLI](https://artifacts.duckdb.org/staged/v2.0-cyanoptera/duckdb-cli-linux-amd64.tar.gz)
reported `v2.0.0-alpha48630` (`f2ba7850dd`). Its
[pinned Quack revision](https://github.com/duckdb/duckdb/blob/f2ba7850dd/.github/config/extensions/quack.cmake)
is `e7a9be3f8c1c3be5946e534c96f15b56b81dc026` (the loaded signed core
extension reports `e7a9be3f8c`); httpfs reports `957e7ff8eb`. Both installed
from `core` in an isolated directory without `-unsigned`. Quack still uses
protocol v3; the message schema retains its field IDs, including FETCH field
1, which was renamed from `uuid` to `query_uuid` in upstream source.

Against this signed server, the current `main` source (`0.9.0-SNAPSHOT`, with
the same v3 implementation as released 0.8.0) passes
`QUACK_IT_DUCKDB=/tmp/opencode/quack-v2-oct16/duckdb mvn clean verify`:
**652 tests, zero failures/errors, three skips** (optional external v3 pair
and two IPv6-dependent tests). Before the test adjustment, one assertion
expected our decoder's `QuackUnsupportedTypeException` for
`'24:00:00.000000001'::TIME_NS`; DuckDB now rejects that invalid literal
before sending it. The test accepts only the expected server out-of-range
error or the prior driver-side rejection, so it remains meaningful on both
versions. This is not a v3 wire break and does not certify final DuckDB 2.0.

## DuckDB 2.0.0 Final Release Verification (2026-10-21)

The official [DuckDB 2.0.0 release](https://github.com/duckdb/duckdb/releases/tag/v2.0.0)
CLI reports `v2.0.0` (`d54cbbe3df`). Its
[Quack pin](https://github.com/duckdb/duckdb/blob/v2.0.0/.github/config/extensions/quack.cmake)
is `f96420ed46aee9c42064bdb99ddfd1230d6c0086`: signed core Quack reports
`f96420ed46`, protocol v3. The signed core httpfs reports `77392a7207`. Both
were installed from `core` in an isolated directory with unsigned extensions
and unsafe crypto disabled.

The current `0.9.0-SNAPSHOT` source (same v3 runtime implementation as 0.8.0)
was tested with the released server:

| Profile | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `QUACK_IT_DUCKDB=/tmp/opencode/quack-v2-release/duckdb mvn clean verify` | 652 | 0 | 0 | 3 |
| `-Poracle -Dduckdb.jdbc.version=2.0.0.0 clean verify` with that CLI | 658 | 0 | 0 | 3 |

The oracle profile also passed on Java 17. No required integration test was
skipped; the three skips are the optional external preview-artifact test and
two IPv6-dependent transport tests. The 1.5.6/v1 oracle profile also passes
on Java 17, preserving the backwards-compatibility gate.

**Intentional parity exception:** DuckDB's
[dedicated TUPLE type](https://github.com/duckdb/duckdb/pull/23465) replaced
unnamed structs. DuckDB's C API still exposes tuples as unnamed structs, but
[duckdb-java#728](https://github.com/duckdb/duckdb-java/pull/728) temporarily
excluded TUPLE from its type-coverage test; the released JDBC driver has no
TUPLE mapping and falls back to `String`/`Types.OTHER`. We preserve the
positional `java.sql.Struct`/`Types.STRUCT` behavior shipped in 0.8.0 and
assert that divergence explicitly in the version-matched oracle test rather
than copying an upstream JDBC fallback. Full suite success therefore means
wire/JDBC coverage with this documented exception, **not** identical native
JDBC `getObject()` results for every logical type.

## Driver Lifecycle and Zoned-Nanosecond Follow-up (2026-10-29)

### Scope and adoption status

The starting runtime is published `dev.brikk.duckdb:quack-jdbc:0.10.0`
(`v0.10.0`, `087a667`); `main` subsequently changed only the development version
to `0.11.0-SNAPSHOT`. The follow-up was prepared on `fix/quack-lifecycle-nanos`
at `104d1fc0f0597050bd738e705eb7c3be7aa5816c`, without publication or DuckBridge
changes. After reviewing this handoff, the user authorized **0.11.0** for release
on 2026-10-29. Release preparation is on `release/v0.11.0`; the `v0.11.0` tag
uses the existing signed-server CI gates and automatic Maven Central publication.
DuckBridge adoption remains separate work: no dependency substitution,
`mvn install`, or DuckBridge checkout change is part of this driver release.

### Implemented behavior

- **Cancellation:** protocol-v3 message 12, body field 1 containing the owning
  statement's hugeint query UUID. The statement publishes its token before its
  PREPARE is sent. CANCEL uses a separate concurrent HTTP request, not an execution
  lock held by PREPARE/FETCH. A short dispatch gate prevents a new PREPARE from
  crossing an in-progress CANCEL: the pinned server's UUID check and abort are
  separate operations. Old statements only cancel their own local buffered rows
  when a newer query has superseded them; no statement cancellation sends UUID 0.
- Cancellation raises SQL state **57014**, including buffered-row getters,
  `ResultSet.next()`, blocked PREPARE, and a successful FETCH reply racing CANCEL.
  Idle/completed cancellation is a no-op. v1 explicitly throws
  `SQLFeatureNotSupportedException`. No query is retried or reconnected.
- If cancellation cannot be confirmed, the connection/session is explicitly
  closed. This covers the pinned server's startup race when the UUID has not yet
  been installed, as well as transport failure. There is no wildcard fallback.
  A server `must_invalidate` response retains that flag and its diagnostics.
  Confirmed cancellation permits normal reuse; manual transactions remain
  caller-managed and may require rollback after interruption.
- **Inline prefix:** v3's default PREPARE hint is 1, preserving an initial batch
  for DML counts/errors. APPEND still requests zero inline rows. Neither server
  batch settings nor decode budgets are implicitly changed.
- **Teardown:** close stops heartbeats, marks the session closed, and bounds the
  complete DISCONNECT operation (headers and body) to approximately two seconds.
  A timed-out operation is interrupted, not retried; repeated close is local.
  Normal query timeouts are not shortened. A custom transport must cooperate with
  interruption and concurrent control traffic to release its own resources; an
  uncooperative transport cannot block the caller but may retain a daemon worker.
- **TIMESTAMPTZ_NS:** DuckDB logical ID 33 maps to physical INT64 signed epoch
  nanoseconds. Finite values return UTC `OffsetDateTime`; Timestamp/Calendar reads
  preserve their instant and nanoseconds. Metadata reports `TIMESTAMPTZ_NS`,
  `Types.TIMESTAMP_WITH_TIMEZONE`, `OffsetDateTime`, precision 35 and scale 9.
  Binary APPEND is exact. Infinities are explicitly unsupported, and validity-
  masked NULL payloads are not converted. No zoned timestamp text parsing is used.

### Important correction to the original streaming diagnosis

At signed Quack `f96420ed46`, PREPARE's counter increments by **actual rows**
(`inline_rows += entry.rows`), not by the number of batches. It nevertheless
drains a whole batch before checking that counter, so a hint of 1 selects one
initial nonempty batch, **not one row or a decoded-memory bound**.

The fragment builder also counts actual rows (`rows += chunk.size()`). A highly
compressed sequence can therefore remain one server batch and still materialize
millions of rows before the first JDBC row, with either the old or new hint.
Exact observations with the new hint, signed DuckDB 2.0.0, and five million rows:

| Query shape | Server target batch bytes | Initially materialized rows |
| --- | --- | ---: |
| Plain `range` | Default | 5,000,000 |
| Non-sequential BIGINT projection | Default | 4,188,160 |
| Plain `range` | 16,384 | 1,118,208 |
| Non-sequential BIGINT projection | 16,384 | 2,048 |

These are measured observations, not row-count guarantees. The non-sequential
projection is `(hash(i) % 9223372036854775807)::BIGINT`.
The driver does not silently alter `quack_target_batch_bytes`: that is a
server/session tuning decision with throughput implications. The unmodified
DuckBridge strict gate based on an untuned, compressed plain range is therefore
**not certified by this driver change alone**. Coordinate server batch sizing
or use a demonstrably multi-batch query before injecting a mid-stream fault.
Oversized decoded batches still fail under the unchanged safety budgets rather
than silently returning a partial result or raising those budgets automatically.

Pinned sources:

- [PREPARE inline drain and CANCEL handler](https://github.com/duckdb/duckdb-quack/blob/f96420ed46aee9c42064bdb99ddfd1230d6c0086/src/quack_server.cpp).
- [Actual-row counting in the fragment builder](https://github.com/duckdb/duckdb-quack/blob/f96420ed46aee9c42064bdb99ddfd1230d6c0086/src/quack_fetch_collector.cpp).
- [DuckDB 2.0 logical type IDs](https://github.com/duckdb/duckdb/blob/v2.0.0/src/include/duckdb/common/types.hpp).

### Verification

Both full profiles ran on Java **17.0.2**, Maven **3.9.16**, Linux x86-64, with
isolated signed core extensions and unsigned extensions disabled:

| Server / oracle | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| DuckDB 1.5.6, Quack `7e80f7f`, JDBC 1.5.6.0 | 698 | 0 | 0 | 3 |
| DuckDB 2.0.0, Quack `f96420ed46`, JDBC 2.0.0.0 | 698 | 0 | 0 | 3 |

The integration report gate passed **16/16 classes, 50 total reports** on each
profile. No required integration test was skipped. The three known skips remain
the external unsigned preview pair and two IPv6/address-dependent tests. v3-only
test bodies are version-gated; the v1 profile verifies explicit unsupported
cancellation instead of claiming v3 behavior on an old server.

New regression coverage includes:

- Latch-controlled blocked PREPARE and FETCH, exact UUID ownership, repeated,
  idle, completed and superseded cancellation, the cancel/new-PREPARE gate,
  explicit close on startup mismatch, and server `must_invalidate` preservation.
- Signed-server PREPARE cancellation observed through `quack_active_connections()`
  with a 10-second server fallback and a cancellation result under three seconds.
- Signed-server FETCH with its already-produced HTTP reply held in a concurrent
  proxy while CANCEL runs; consuming the reply fails with 57014, never clean EOF.
- A huge producer with a 64-KiB result buffer, no further FETCH consumption,
  successful cancellation, explicit rollback, no committed insert, and connection
  reuse. The test allows the producer to reach capacity before cancelling.
- Real HTTP connection cuts after verified partial materialization, both with
  default-sized non-sequential batches and a 20-million-row compressed range with
  explicit smaller server batches. A subsequent FETCH fails with SQLException;
  stale row getters fail rather than expose partial success.
- DISCONNECT blackholes before headers and after a partial body, using a normal
  30-second request timeout. Close returns under three seconds, sends only once,
  and is idempotent. A legitimate 2.2-second query still succeeds. An interruptible
  custom transport is also tested, as is single-attempt control-message dispatch.
- TIMESTAMPTZ_NS logical/physical mapping, negative nanos, signed finite endpoints,
  all supported compressed vector forms, NULL validity, infinity rejection,
  metadata, multiple server timezones, Calendar/Timestamp reads, and APPEND checked
  against **server-side `epoch_ns(v)`**, not merely a symmetric driver round trip.

Commands (replace CLI paths as needed):

```bash
mise exec java@17.0.2 -- mvn --batch-mode --no-transfer-progress -Poracle -Dquack.it.required=true clean verify
python3 scripts/check-integration-reports.py
QUACK_IT_DUCKDB=/path/to/duckdb-2.0.0 mise exec java@17.0.2 -- mvn --batch-mode --no-transfer-progress -Poracle -Dduckdb.jdbc.version=2.0.0.0 clean verify
QUACK_IT_EXPECTED_VERSION=v2.0.0 QUACK_IT_EXPECTED_QUACK=f96420ed46 python3 scripts/check-integration-reports.py
```

Local evidence is under `/tmp/opencode/quack-lifecycle-v1-java17.log`,
`/tmp/opencode/quack-lifecycle-v2-java17.log`, and
`/tmp/opencode/quack-lifecycle-prefix-probe.log`. These are local artifacts, not
release dependencies. The DuckBridge logs and Kotlin acceptance wiring supplied
in the handoff were read only; no DuckBridge acceptance task was run or claimed
passing with a substituted artifact.

Remaining boundaries: cancellation may use the configured HTTP timeout if the
server/network cannot answer; ambiguous cancellation closes rather than promises
reuse. General JDBC query/network deadlines and whole-body query deadlines remain
separate work. Timestamp parameter interpolation retains existing microsecond
precision; exact nanosecond APPEND/reads do not imply nanosecond prepared binding.
The later authorization to release 0.11.0 does not certify DuckBridge adoption
or change these verification boundaries.

### 0.11.0 publication confirmation

The `v0.11.0` release tag points to `60a6367592a9bb1f9371a806f9ab058e961a2241`.
[Release CI](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/40964438288)
passed both Java 17 signed-server oracle profiles: **698 tests each, zero
failures/errors, one optional external-preview skip**. Both integration report
gates passed 16/16 classes and 50 total reports; no required integration test
was skipped. The two local IPv6/address-dependent skips did not occur in CI.

The automatic Central deployment reached **published** on 2026-10-29, and the
public Maven repository's POM and JAR were retrieved and verified as
`dev.brikk.duckdb:quack-jdbc:0.11.0`. The matching
[GitHub release](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.11.0)
includes binary, sources, Javadoc, and checksum assets. DuckBridge was not
modified; its adoption of this driver remains separate qualification work.

## Bounded CANCEL under Response Loss (2026-10-30)

### Source and release scope

Prepared on `fix/cancel-ack-deadline`, starting from the 0.11.0 runtime, at source
commit `4d3c966f8ebaf4666247e90f316b06e2ea5bdf66`. The implementation was verified
as **0.11.1-SNAPSHOT** without publication or DuckBridge changes. The user then
authorized release **0.11.1** to Maven Central on 2026-10-30. Release preparation
is on `release/v0.11.1`; the `v0.11.1` tag uses the existing signed-server CI gates
and automatic publication. No `mvn install`, DuckBridge source change, dependency
substitution, or DuckBridge Gradle qualification task is part of this release.

### Missing contract and fix

Published 0.11.0 correctly sends UUID-targeted CANCEL and bounds DISCONNECT, but
CANCEL still inherits the normal HTTP request timeout. With a 30-second timeout,
a lost CANCEL acknowledgement holds the dispatch gate and delays fail-closed
cleanup. Merely capping `HttpRequest.timeout` is insufficient when the streaming
body handler has already received headers and then stalls reading the body.

The patch adds `DEFAULT_CANCEL_TIMEOUT` (two seconds) and two complementary caps:

- The HTTP layer caps CANCEL's request timeout at two seconds, just as DISCONNECT
  has its own two-second cap. A shorter configured HTTP timeout remains shorter;
  ordinary PREPARE/FETCH requests retain their configured timeout. Both controls
  remain single-attempt operations.
- The session waits at most approximately two seconds for the **entire CANCEL**,
  including transport setup, headers, response-body consumption and decoding.
  At expiry it cancels and interrupts the daemon control worker. The existing
  fail-closed path then closes the connection with the independent two-second
  DISCONNECT budget and returns an explicit SQLException.

Thus lost CANCEL and DISCONNECT acknowledgements consume approximately four
seconds plus scheduling overhead, rather than the general 30/60-second timeout.
Healthy cancellation still interrupts the real query with SQLState 57014 and
permits autocommit reuse. No SQL is replayed, no wildcard UUID is used, and no new
PREPARE can overtake an ambiguous cancellation. Typed error diagnostics and
`must_invalidate` survive the worker boundary; an invalidated server is not sent
a DISCONNECT. Explicit transaction rollback remains caller-managed.

### Verification

The new HTTP-header, partial-body and uncooperative-custom-transport regressions
were first run against the unfixed source: all three failed because the cancel
caller was still waiting after five seconds. After the patch, each returns an
explicit failure with a closed connection within the five-second allowance.

Both full oracle profiles pass on Java 17.0.2 / Maven 3.9.16 / Linux x86-64:

| Signed server / version-matched oracle | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| DuckDB 1.5.6, Quack `7e80f7f`, JDBC 1.5.6.0 | 704 | 0 | 0 | 3 |
| DuckDB 2.0.0, Quack `f96420ed46`, JDBC 2.0.0.0 | 704 | 0 | 0 | 3 |

Both integration report gates passed **16/16 required classes, 51 total reports**.
There were no skipped required integration tests; the three known optional/
environment skips are unchanged. v3-only real-server tests are version-gated.
This is local verification, not a new remote CI or publication result.

Coverage includes:

- Missing CANCEL response headers and partially delivered bodies, each with a
  30-second normal timeout and a simultaneously blackholed DISCONNECT.
- A real signed v3 server running a marked, independently observed active query
  with a 15-second server fallback. A concurrent HTTP proxy forwards the actual
  CANCEL and DISCONNECT to the server but withholds both acknowledgements (either
  all headers or partial bodies). Cancellation fails closed inside five seconds,
  with one request per control, exact owning UUID, and no SQL replay. The response
  path is restored in `finally`, and execution/cancellation callers are joined.
- Uncooperative custom transport I/O that ignores interruption for both controls:
  the caller is still bounded, the ambiguous connection stays closed even after
  late success responses, and a new PREPARE waiting behind the gate fails locally.
- Existing healthy cancellation, SQLState 57014, autocommit reuse, UUID/completion
  races, superseded statements, transaction rollback and `must_invalidate` tests.
- Existing DISCONNECT blackholes before headers and after a partial body, a
  legitimate query taking longer than two seconds, a configured 30-second normal
  HTTP timeout, and real mid-stream connection-cut failures rather than partial EOF.

Reproduce with the same two oracle commands and integration-report checks shown
in the preceding follow-up. The local v2 CLI is
`/tmp/opencode/quack-must-invalidate-v2/duckdb` (released 2.0.0), and evidence is in:

- `/tmp/opencode/quack-cancel-deadline-red.log`
- `/tmp/opencode/quack-cancel-deadline-v3-focused.log`
- `/tmp/opencode/quack-cancel-deadline-v1-java17.log`
- `/tmp/opencode/quack-cancel-deadline-v2-java17.log`

### Remaining limitations

The control **caller** is bounded; arbitrary custom I/O cannot be forcibly
terminated. A custom transport that ignores interruption may keep a daemon
worker/resources until its I/O returns. The original PREPARE/FETCH response is not
forcibly aborted by the control deadline and can remain blocked until delivery
resumes, especially after headers have arrived. The new deadline does not shorten
legitimate query deadlines or implement the separate general JDBC/whole-body
query deadline work. If delivery is uncertain,
closing the client does not establish the final server-side SQL outcome, so no
automatic SQL retry is safe. The 0.11.0 compressed-batch/row-bound caveat is unchanged.

### 0.11.1 publication confirmation

The `v0.11.1` release tag points to `58da468121737b1d12e3a4ebd099107ab9b0d603`.
[Release CI](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/41109524395)
passed both Java 17 signed-server oracle profiles: **704 tests each, zero
failures/errors, one optional external-preview skip**. Both integration gates
passed 16/16 required classes and 51 total reports. No required integration test
was skipped; the two local IPv6/address-dependent skips did not occur in CI.

Automatic deployment reached **published** on 2026-10-30. The public Maven POM
and JAR were retrieved and verified as `dev.brikk.duckdb:quack-jdbc:0.11.1`.
The public binary JAR's SHA-256 is
`8cff98a94eb793569f3c6de0bc840edfc7ba9bb1118e5fb0f30ac0429d9fe3b3`.
The [GitHub release](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.11.1)
also contains binary, sources, Javadoc, and checksum assets. DuckBridge was not
modified or requalified with a substituted dependency as part of publication.

## Triggered DML Affected-Row Classification (2026-10-31)

### Scope and diagnosis

Prepared on `fix/triggered-dml-counts`, based on published 0.11.1's runtime, at
source commit `4762e78e59c28f27c9e63c8a02f389d037f07ae8`. After implementation,
the user authorized pushing to `main`; signed-server
[CI](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/41172031771) passed
and published snapshot `0.12.0-20261031.051948-3`. The user confirmed that snapshot
tested successfully and authorized release **0.12.0** on 2026-10-31. Release
preparation is on `release/v0.12.0`, using the existing tag-driven CI and automatic
Maven Central publication. No local Maven installation or DuckBridge checkout/
dependency change is part of this driver release.

The exact reproducer was independently run with the published 0.11.1 JAR from
Maven Central (SHA-256
`8cff98a94eb793569f3c6de0bc840edfc7ba9bb1118e5fb0f30ac0429d9fe3b3`) and with the
patched driver. Both use released DuckDB 2.0.0 with signed core Quack
`f96420ed46aee9c42064bdb99ddfd1230d6c0086`, negotiated v3:

| Observation | Published 0.11.1 | Patched source |
| --- | --- | --- |
| `execute(INSERT ... VALUES(1),(2))` | `true` | `false` |
| `getUpdateCount()` | `-1` | `2` |
| Raw cursor count name/value for a separate two-row insert | `count_star()` / `2` | `count_star()` / `2` |
| `executeUpdate()` for a further two-row insert | `0` | `2` |
| Final target / audit rows after the three intentional inserts | `6` / `6` | `6` / `6` |

The new signed-server integration suite also uses the version-matched native
`duckdb_jdbc` 2.0.0.0 Quack client, with its own isolated signed core installation.
It independently verifies `count_star() = 2` and exact final target/audit effects.
The original seven-case regression suite initially failed six classification/
count checks on the unfixed source, while this raw/native count-contract check
already passed. The data and wire count were correct; JDBC result classification
was wrong.

### Fix and safety boundaries

Quack's PREPARE response carries names, types, values and a query UUID, but no
statement-kind/result-kind tag. Adding `count_star()` to a global name allowlist
would therefore misclassify ordinary `SELECT count(*)` as an update count.
The existing `Count` and `rows_affected` allowlist could already swallow SELECT
aliases and one-row DML RETURNING results with those names.

Classification now combines:

- A single non-null, nonnegative integral value with one column named `Count`,
  `rows_affected` or `count_star()` (case-insensitive).
- The submitted statement's SQL context. INSERT, UPDATE, DELETE and MERGE without
  a top-level RETURNING clause may expose the wire value as their affected count.
  Legacy Count/rows_affected command results retain their DDL/control handling.
  SELECT and RETURNING preserve their actual ResultSet, metadata and lazy FETCH.

The lexical context reader handles CTEs (including recursive/keyed and materialized
forms), nested comments, quoted identifiers, ordinary/escaped/dollar strings,
balanced groups and trailing empty statements. It is not a full SQL parser and
does not rewrite, validate or execute SQL. Unknown and multi-statement text is
conservatively retained as a wire ResultSet rather than guessing which command a
count-shaped result belongs to. General JDBC multiple-result support is unchanged.

No metadata probe, SELECT-based count reconstruction, or SQL replay is introduced.
All public/prepared execution and batch paths still converge on the same single
execution. Count values above int range retain the existing Integer.MAX_VALUE
cap, including unsigned BigInteger counts without signed-long wrapping. A final
cancellation check before publishing classification prevents a concurrent cancel
from turning into successful DML completion.

DuckDB 2.0.0 explicitly rejects RETURNING on tables with FOR EACH ROW triggers.
That remains a server SQLException with no fabricated count or partial effects;
the suite separately verifies supported RETURNING with statement-level triggers.
No server trigger capability or wire change is claimed by this fix.

### Verification

Full oracle profiles passed on Java 17.0.2, Maven 3.9.16, Linux x86-64, with isolated
signed core server extensions and unsigned extensions disabled:

| Server / version-matched oracle | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| DuckDB 1.5.6, Quack `7e80f7f`, JDBC 1.5.6.0 | 724 | 0 | 0 | 3 |
| DuckDB 2.0.0, Quack `f96420ed46`, JDBC 2.0.0.0 | 724 | 0 | 0 | 3 |

Both integration report gates passed **17/17 required classes, 54 total reports**.
No required integration test was skipped; the three optional/environment skips
are unchanged. Trigger-specific real-server test bodies are v3-only; ordinary
SELECT/RETURNING and legacy driver behavior run on both supported versions.

New coverage includes row-trigger INSERT execute/update/prepared/batch counts;
statement-trigger prepared UPDATE/DELETE and zero-match counts; exact source/audit
counts, values and sums detecting duplicate execution; explicit rollback after a
trigger constraint error; SELECT count aliases; statement-trigger RETURNING count
aliases and zero-row RETURNING; raw and native Quack observations; CTE/comment/
literal handling; malformed count shapes; large unsigned count clamping; one-row
inline SELECT/RETURNING results that remain lazy; and cancellation during result
classification. The full existing bounded CANCEL/DISCONNECT, session invalidation,
transaction, batching and streaming-fault suites also pass unchanged.

Commands are the same full oracle/report-gate commands used above, with the 2.0.0
CLI selected by `QUACK_IT_DUCKDB` and `-Dduckdb.jdbc.version=2.0.0.0` for that run.
Local evidence:

- `/tmp/opencode/quack-triggered-counts-red.log`
- `/tmp/opencode/quack-triggered-counts-published-probe.log`
- `/tmp/opencode/quack-triggered-counts-fixed-probe.log`
- `/tmp/opencode/quack-triggered-counts-unit.log`
- `/tmp/opencode/quack-triggered-counts-v1-java17.log`
- `/tmp/opencode/quack-triggered-counts-v2-java17.log`

DuckBridge's `TestQuackTriggeredCountContracts.kt` and `TestTriggeredWrites.kt`
were read for the requested contracts, not modified or executed with a substituted
dependency. This is driver-side test evidence, not a claim that the consumer's
strict triggered-write qualification has been rerun or adopted.

### 0.12.0 publication confirmation

The `v0.12.0` release tag points to `9ef00f3c75433e9cb71d265ccd69df88147480bd`.
[Release CI](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/41172849401)
passed both Java 17 signed-server oracle profiles: **724 tests each, zero
failures/errors, one optional external-preview skip**. Both integration gates
passed 17/17 required classes and 54 total reports; no required integration test
was skipped. The two local IPv6/address-dependent skips did not occur in CI.

Automatic deployment reached **published** on 2026-10-31. The public Maven POM
and JAR were retrieved and verified as `dev.brikk.duckdb:quack-jdbc:0.12.0`.
The public binary JAR's SHA-256 is
`0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`.
The [GitHub release](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.12.0)
also contains binary, sources, Javadoc, and checksum assets. This release uses
the implementation from the user-confirmed snapshot; only version and release
documentation changed. DuckBridge was not modified as part of publication.
