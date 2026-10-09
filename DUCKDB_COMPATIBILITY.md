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
