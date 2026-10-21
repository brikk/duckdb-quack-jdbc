# CLAUDE.md

Project notes for Claude and other contributors.

## What this is

A JDBC driver for [DuckDB's Quack remote protocol](https://duckdb.org/docs/current/quack/overview).
Lets JVM tools (DBeaver, IntelliJ, dbt, Spark, etc.) connect to a remote
DuckDB server with a familiar `jdbc:quack://host:port?token=…` URL.

## Package layout

```
com.gizmodata.quack.jdbc
├── codec/        BinaryReader, BinaryWriter, HugeIntParts, QuackConstants
├── type/         LogicalType, LogicalTypeId, PhysicalType, ExtraTypeInfo, codec
├── message/      MessageType, MessageHeader, QuackMessage (sealed), MessageCodec,
│                 DataChunk, DecodedVector, VectorCodec
├── transport/    QuackUri (URL parser), QuackHttpTransport (java.net.http)
├── sql/          QuackDriver, QuackConnection, QuackStatement,
│                 QuackPreparedStatement, QuackResultSet,
│                 QuackResultSetMetaData, QuackDatabaseMetaData,
│                 SqlLiteral (parameter interpolation),
│                 SkeletalConnection/Statement/ResultSet (throws-by-default bases)
└── Quack*Exception   error hierarchy
```

The `codec`, `type`, `message`, and `transport` packages are reusable for
an ADBC driver (Go or otherwise). The `sql` package is the JDBC-specific
surface.

## Wire format reference

Wire-format details ported clean-room from
[`@quack-protocol/sdk`](https://github.com/tobilg/quack-protocol) by Tobi
(MIT). The TypeScript reference lives at `/tmp/quack-ref/` after running:

```bash
mkdir -p /tmp/quack-ref
for f in binary constants messages logical-types vector protocol values \
         client errors sql builders index; do
  gh api repos/tobilg/quack-protocol/contents/src/$f.ts --jq .content \
    | base64 -d > /tmp/quack-ref/$f.ts
done
```

## Build & test

```bash
mvn package          # build
mvn test             # unit + integration (needs duckdb on PATH)
mvn -Poracle -Dquack.it.required=true verify  # required CI profile
```

Integration tests spawn a real DuckDB CLI as a Quack server. They auto-skip
when `duckdb` isn't on PATH; override with `QUACK_IT_DUCKDB=/path/to/duckdb`.

## Quack extension version

As of DuckDB v1.5.3 ("Variegata"), `quack` ships as a **core** signed
extension — the integration fixture runs `INSTALL quack; LOAD quack;`
against a stock `duckdb` binary (no `-unsigned`, no `core_nightly`).
For pre-1.5.3 DuckDB builds, use `INSTALL quack FROM core_nightly` with
`duckdb -unsigned`. CI, `mise.toml`, and the `-Poracle` `duckdb_jdbc`
dependency are pinned to released DuckDB 1.5.6.

The v1 wire format was clean-room ported from `@quack-protocol/sdk`; v3 support
tracks DuckDB's pinned Quack schema. The full driver suite passes against
DuckDB 1.5.6's signed core Quack v1 and released DuckDB 2.0.0's signed
core Quack v3. For exact revisions and test results, see
[release compatibility](dev-docs/DUCKDB_COMPATIBILITY.md).
The original review and historical verification are in
[dev-docs/archive/CODE_REVIEW.md](dev-docs/archive/CODE_REVIEW.md).

## DBeaver metadata reference

`QuackDatabaseMetaData` mirrors DuckDB's own JDBC driver
(`org.duckdb.DuckDBDatabaseMetaData`) query-for-query so DBeaver sees the
same shape it does from a native DuckDB connection. When updating queries,
cross-reference DuckDB's `src/main/java/org/duckdb/DuckDBDatabaseMetaData.java`.

## Conventions

- Java 17 floor — broadest runtime compatibility (DBeaver ships with JRE 17).
  No pattern-matching switches; use `instanceof` chains.
- No runtime dependencies. JDK `java.net.http.HttpClient` for transport.
- No emoji in code, javadocs, or commit messages.
- Throw `SQLFeatureNotSupportedException` for unimplemented JDBC surface
  (see the `Skeletal*` base classes).
- DBeaver compatibility is a hard requirement — when changing
  `QuackDatabaseMetaData`, verify against DuckDB's reference before
  diverging.

## Release process

1. Verify the signed-server oracle profile and any supported preview profile.
2. Create a release branch, bump `pom.xml`, update `CHANGELOG.md` and `README.md`.
3. Push the version tag from that branch. Tag CI tests and publishes to Maven
   Central and GitHub Releases. After publication, advance `main` and bump its
   next `-SNAPSHOT` version.

## Roadmap

- FSST vector decoding
- Native PREPARE_REQUEST parameter binding once the protocol surfaces it
- Companion ADBC driver in Go (same wire codec layer ported)
