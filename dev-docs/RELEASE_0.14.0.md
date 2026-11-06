# 0.14.0: named wire fields and strict ResultSet conversions

Release date: 2026-11-06. Coordinates: `dev.brikk.duckdb:quack-jdbc:0.14.0`.
Minor version: this release removes a public method and changes `ResultSet`
getter behavior, so it is not a patch on 0.13.x. The immutable `v0.14.0` tag
identifies the release commit.

## Publication confirmation

- Release commit: `66208cc55b30086e6994689cd63df713e63f0484`, head of
  `release/0.14.0`.
- [Tag / GitHub Release v0.14.0](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.14.0)
  is a normal release, not a draft or prerelease, with binary/sources/Javadoc
  jars and `SHA256SUMS`.
- [Release CI run 42294804031](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/42294804031)
  succeeded on the first attempt: both signed-server Java 17 jobs passed
  **787 tests, zero failures/errors, one optional preview skip**, and the report
  checker confirmed 17/17 required integration classes with the 2.0.0 identity
  pins (`runtime=v2.0.0`, Quack `f96420ed46`).
- Central deployment `26f1e89a-73a2-43f2-be9c-b7d0913da60e` reached **published**
  at 2026-11-06 21:23:00 UTC. `maven-metadata.xml` reports `0.14.0` as both
  `latest` and `release`.
- **Central binary JAR SHA256:**
  `e6a80070c66129e54d70fcd29d3693be6dd5dd0160176136f1c42034137bf8a5`.
  The GitHub asset (`7aab3ff6…bbc9`) is packaged by a separate CI job and differs
  only in archive metadata: all 158 entries are byte-identical. Production
  classes are major version 61 (Java 17).

## Changes

- Wire field ids are `WireField` enums grouped per serialized object
  (`MessageFields`, `ChunkFields`, `TypeFields`). Protocol errors for a
  mismatched required field name it, e.g. `ROW_COUNT(100)`. No bytes on the
  wire change; the `int` overloads on `BinaryReader`/`BinaryWriter` remain.
- `getBoolean` accepts booleans, numbers and `true/t/1`, `false/f/0`
  (case-insensitive) and throws `SQLException` otherwise, instead of returning
  `false`. `getBigDecimal`, `getDate`, `getTime` and `getTimestamp` throw
  `SQLException` for unconvertible values instead of leaking
  `NumberFormatException`/`IllegalArgumentException`; the plain and `Calendar`
  overloads share one parser.
- Removed `QuackSession.connect(QuackUri, QuackTransportFactory)`. The
  `QuackSession(QuackUri, QuackHttpTransport)` constructor is retained for
  binary compatibility.
- Comments that narrated earlier diffs and two warning-suppression classes were
  removed. `CLAUDE.md` now states both conventions.
- The integration fixture sets `DUCKDB_AGENT_MODE=0` so DuckDB 2.0's agent
  output mode cannot alter CLI output when the suite runs from an agent shell.

## Downstream check

`ducklake-catalog` (peer checkout at `7bce5da`) pins `quack-jdbc = "0.13.0"`,
uses no `QuackSession`/`QuackTransportFactory` API directly, reads `getBoolean`
only from a boolean column, and does not catch unchecked exceptions from driver
getters. No change is required there to adopt 0.14.0.

## Verification matrix

Local runs used Maven 3.9.16, signed released servers in isolated fixture
directories, and the matched native `duckdb_jdbc` oracle. The 2.0.0 CLI was
restored from the official release archive; its SHA256 matches the
`c41f957d…43fb` recorded for 0.13.1.

| Java | Signed server / matched native JDBC oracle | Tests | Failures | Errors | Skips |
| --- | --- | ---: | ---: | ---: | ---: |
| 17.0.2 | DuckDB 1.5.6 (`069cc9f9b5`) / JDBC 1.5.6.0 | 787 | 0 | 0 | 3 |
| 17.0.2 | DuckDB 2.0.0 (`d54cbbe3df`) / JDBC 2.0.0.0 | 787 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 1.5.6 (`069cc9f9b5`) / JDBC 1.5.6.0 | 787 | 0 | 0 | 3 |
| 21.0.2 | DuckDB 2.0.0 (`d54cbbe3df`) / JDBC 2.0.0.0 | 787 | 0 | 0 | 3 |

The three local skips are the optional external preview pair and the two
IPv6/multi-address transport tests; CI shows only the preview skip. Both CI jobs
were also replicated locally from a clean `target/` with `AI_AGENT` set in the
shell, including `scripts/check-integration-reports.py` with and without the
2.0.0 identity pins; a deliberately wrong pin fails as expected.
