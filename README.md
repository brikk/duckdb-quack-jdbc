# quack-jdbc

**A JDBC driver for [DuckDB's Quack remote protocol](https://duckdb.org/docs/current/quack/overview).**

Lets any JVM tool — DBeaver, IntelliJ DataGrip, dbt, Spark, your own
service — connect to a DuckDB server over the Quack wire protocol with a
familiar `jdbc:quack://` URL.

[![Maven Central](https://img.shields.io/maven-central/v/dev.brikk.duckdb/quack-jdbc?label=Maven%20Central&logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/dev.brikk.duckdb/quack-jdbc)
[![Latest Release](https://img.shields.io/github/v/release/brikk/duckdb-quack-jdbc?label=Latest%20Release&logo=github&sort=semver)](https://github.com/brikk/duckdb-quack-jdbc/releases/latest)
[![Download latest jar](https://img.shields.io/badge/download-quack--jdbc.jar-success?logo=java&logoColor=white)](https://github.com/brikk/duckdb-quack-jdbc/releases/latest/download/quack-jdbc.jar)
[![GitHub Repo](https://img.shields.io/badge/github-brikk%2Fduckdb--quack--jdbc-181717?logo=github)](https://github.com/brikk/duckdb-quack-jdbc)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Status:** Experimental / alpha. This driver uses stock Quack protocol v1,
> tested against DuckDB 1.5.5 and its signed core Quack extension, with native
> `duckdb_jdbc` 1.5.5.0 as a behavioral oracle. No custom server extension or
> private protocol capability is required.

## Quickstart

### 1. Start a Quack server (DuckDB v1.5.3+)

```sql
-- in any DuckDB session
INSTALL quack;
LOAD quack;
CALL quack_serve('quack:127.0.0.1:9494', token=>'my-secret-token');
```

The `quack` extension is signed and lives in the **core** repository as
of DuckDB v1.5.3, so no `-unsigned` flag and no `core_nightly` repository
are required. (Earlier `1.5.x` builds need `INSTALL quack FROM core_nightly`
with `duckdb -unsigned`.)

### 2. Add the driver to your project

**Maven:**

```xml
<dependency>
    <groupId>dev.brikk.duckdb</groupId>
    <artifactId>quack-jdbc</artifactId>
    <version>0.6.0</version>
</dependency>
```

**Gradle:**

```groovy
implementation "dev.brikk.duckdb:quack-jdbc:0.6.0"
```

**Direct jar download** (for DBeaver, DataGrip, or any tool that takes a `.jar`):

| Asset                                                                                                          | Description                                                    |
|----------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| [`quack-jdbc.jar`](https://github.com/brikk/duckdb-quack-jdbc/releases/latest/download/quack-jdbc.jar)            | Latest release — un-versioned filename, always the newest jar  |
| [`quack-jdbc-sources.jar`](https://github.com/brikk/duckdb-quack-jdbc/releases/latest/download/quack-jdbc-sources.jar) | Latest sources jar                                            |
| [`quack-jdbc-javadoc.jar`](https://github.com/brikk/duckdb-quack-jdbc/releases/latest/download/quack-jdbc-javadoc.jar) | Latest javadoc jar                                            |
| [GitHub releases page](https://github.com/brikk/duckdb-quack-jdbc/releases)                                       | All versioned jars + SHA256 checksums for every release        |

### 3. Connect and query

```java
import java.sql.*;
import java.util.Properties;

public class Demo {
    public static void main(String[] args) throws SQLException {
        Properties props = new Properties();
        props.setProperty("token", "my-secret-token");

        try (Connection conn = DriverManager.getConnection(
                     "jdbc:quack://127.0.0.1:9494", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 42 AS answer, 'hello duckdb' AS greeting")) {
            while (rs.next()) {
                System.out.println(rs.getInt("answer") + " / " + rs.getString("greeting"));
            }
        }
    }
}
```

## Connection URL

```
jdbc:quack://host[:port][/database][?token=…&tls=…]
```

| Property             | Default | Notes                                                                    |
|----------------------|---------|--------------------------------------------------------------------------|
| `host`               | —       | Required.                                                                |
| `port`               | 9494    | Default Quack port.                                                      |
| `database`           | (none)  | URL path selects an existing server catalog with `USE`; unknown catalogs fail connection. |
| `token`              | (none)  | Authentication token. Prefer an indirect token source for shared configs. |
| `password`           | (none)  | Alias for `token`, useful for tools that expose a password field.         |
| `tokenEnv`           | (none)  | Environment variable containing the authentication token.                 |
| `tokenFile`          | (none)  | Local file containing the authentication token.                           |
| `tls`                | false   | `true` → use `https://` for the underlying HTTP transport.                |
| `useEncryption`      | false   | Alias for `tls` (matches the gizmosql-jdbc-driver convention).            |
| `connectTimeout`     | 10      | HTTP connect timeout, as seconds or an ISO-8601 duration like `PT5S`.     |
| `requestTimeout`     | 60      | Per-request HTTP timeout, as seconds or an ISO-8601 duration like `PT30S`. |
| `maxResponseBytes`   | 67108864 | Maximum HTTP response body bytes (64 MiB); positive integer. |
| `maxDecodedBytes`    | 268435456 | Per-message decoded allocation budget (256 MiB); positive long. |
| `maxNestingDepth`    | 64       | Maximum active decoder nesting frames; integer from 1 to 128. |
| `httpHeader.<Name>`  | (none)  | Extra HTTP header sent with every request (proxy/LB auth). Repeatable. Properties only — rejected on the URL. |

These options can be set on the URL or via `java.util.Properties` passed
to `DriverManager.getConnection`. URL values take precedence. Token
resolution checks `token`, then `password`, then `tokenEnv`, then
`tokenFile`.

`tokenEnv` and `tokenFile` are the exception: they are only accepted via
`Properties` (or a tool's driver-properties panel) and are rejected if
they appear on the URL. A pasted or shared URL must not be able to read
a local secret and send it to whatever host the URL names.

`tls` and its alias `useEncryption` accept `true/false`, `1/0`, `yes/no`,
or `on/off`, case-insensitively with surrounding ASCII whitespace ignored.
Unrecognized nonblank values fail connection setup rather than selecting
plaintext. Missing or blank values retain the existing `false` behavior.
The `tls` key takes precedence over `useEncryption`; URL values take precedence
over connection Properties for the same key.

### Basic timeout configuration

The built-in HTTP transport reads `connectTimeout` and `requestTimeout`
directly from the JDBC URL or connection properties:

```java
try (Connection conn = DriverManager.getConnection(
        "jdbc:quack://127.0.0.1:9494?token=my-secret-token&connectTimeout=5&requestTimeout=30")) {
    // use the connection normally
}
```

The same options can be supplied with `Properties`:

```java
Properties props = new Properties();
props.setProperty("token", "my-secret-token");
props.setProperty("connectTimeout", "5");
props.setProperty("requestTimeout", "PT30S");

try (Connection conn = DriverManager.getConnection("jdbc:quack://127.0.0.1:9494", props)) {
    // use the connection normally
}
```

### Response and decoding limits

The built-in HTTP transport bounds each response body, including chunked
responses. The decoder shares one allocation budget across all columns,
chunks, nested types, and compressed-vector expansion in that message.
Nesting frames include wire objects and inline compressed vectors, not just
SQL type depth. Oversized or malformed inputs fail instead of allocating
from unchecked lengths. Bodies close on failure and are not replayed.

`maxDecodedBytes` is conservative accounting for arrays, scalar objects,
strings, nested containers, and intermediate allocations, not an exact JVM
heap measurement. Fixed-width scalar conversion costs are type-specific and
charged only for non-null materializations; array storage is charged for every
row. Compressed projections charge their source values once, and SEQUENCE
charges its actual int64 conversion path. Valid but very large messages may
still exceed these defaults.
Tune both byte limits for the available heap and concurrent connections;
HTTP buffering/copies and application-retained results use additional memory.
These are per-message limits, not a process-wide memory cap. They do not fix
the separate whole-response deadline limitation of `requestTimeout`.

Existing low-level constructors and `MessageCodec.decode(byte[])` use the
default limits. Low-level callers can supply a `DecodeLimits` to
`BinaryReader`, `MessageCodec.decode`, or the five-argument HTTP transport
constructor. Fully custom transports must enforce their own body limit and
pass `uri.decodeLimits()` to decoding if they want connection overrides.

### Safer token configuration

For desktop tools such as DataGrip, avoid putting `token=...` directly
in the JDBC URL. URLs and plain driver properties are easy to copy, log,
or commit by accident.

Prefer `tokenFile` or `tokenEnv`, set as driver properties (not on the
URL), for shared desktop-tool configurations:

```text
tokenFile=/Users/alice/.config/quack/prod.token
```

or:

```text
tokenEnv=QUACK_TOKEN
```

The token is read at connection time and used for the connection
handshake, but the token itself does not need to be stored in the data
source.

`QuackUri.toString()` is a redacted summary: it exposes only port and TLS
state, not arbitrary string fields or property names/values. Local URL,
timeout, token-source, and extra-header validation errors omit submitted
values and do not retain input-bearing parser exceptions. Operational
accessors still return the original configuration so authentication works.
Do not log those accessors, raw URLs/Properties, or protocol request objects.
Server/query errors and diagnostics from directly supplied custom transport
endpoints are not a general-purpose secret-redaction boundary.

### Fully custom HTTP transport

Applications that need to customize the HTTP layer can bypass
`DriverManager` and pass a transport factory to `QuackDriver`. The factory
receives the same parsed `QuackUri`, so URL parameters and connection
properties are available to custom transports too:

```java
QuackDriver driver = new QuackDriver();
try (Connection conn = driver.connect(
        "jdbc:quack://127.0.0.1:9494?token=my-secret-token&connectTimeout=5&requestTimeout=30",
        new Properties(),
        uri -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(uri.connectTimeout())
                    .build();
            return new QuackHttpTransport(uri.httpUri(), httpClient, uri.requestTimeout(),
                    uri.extraHttpHeaders(), uri.decodeLimits());
        })) {
    // use the connection normally
}
```

## Required Verification

CI uses stock signed core extensions and runs the full oracle profile with
required fixtures. To reproduce:

```bash
QUACK_IT_DUCKDB=/path/to/duckdb \
mvn --batch-mode --no-transfer-progress -Poracle -Dquack.it.required=true verify
```

Required mode fails on missing CLI or oracle dependencies;
CI also rejects missing or skipped integration reports. Fixture logs record
actual server/extension identities and verify signed core loading. Optional local
test runs retain auto-skipping when their fixture is unavailable. This does not
change the independent snapshot publication workflow.

## DBeaver

`quack-jdbc` implements the full JDBC `DatabaseMetaData` surface that
DBeaver uses for catalog browsing, modeled query-for-query on DuckDB's
own JDBC driver. That means:

- Catalog / schema / table / view listing
- Column types, nullability, defaults, comments
- Primary keys
- Imported / exported / cross-reference foreign keys
- Index listing (per index; column-level expansion is a DuckDB limitation)
- `getTypeInfo`, `getFunctions`

Register the driver in **DBeaver → Database → Driver Manager**:

| Field                 | Value                                                |
|-----------------------|------------------------------------------------------|
| Driver Name           | DuckDB (Quack)                                       |
| Driver Type           | Generic                                              |
| Class Name            | `com.gizmodata.quack.jdbc.sql.QuackDriver`          |
| URL Template          | `jdbc:quack://{host}[:{port}]/[{database}]`         |
| Default Port          | `9494`                                               |
| Driver Files          | `quack-jdbc-<version>.jar`                          |

Add `tokenFile` or `tokenEnv` as connection properties for safer shared
configuration. For quick local testing, `token` and `password` are also
supported.

## Building from source

```bash
mvn package
```

Produces `target/quack-jdbc-<version>.jar` with no runtime dependencies
(uses JDK 17 `java.net.http.HttpClient`). On release tags, CI also
publishes an un-versioned `quack-jdbc.jar` to the GitHub release so tools
that want "the latest jar" can fetch a stable URL.

## Testing

```bash
mvn test                       # unit + integration tests
mvn -Dtest='!*Integration*' test   # unit only (no duckdb required)
mvn -Poracle test              # also run the duckdb-jdbc parity suite
```

Integration tests spawn a real DuckDB CLI as a Quack server and exercise
the driver end-to-end: connection handshake, CRUD, multi-chunk fetches,
scalar and nested type round-trips, `DatabaseMetaData` listings, bad-token
auth, concurrent connections, and more. They auto-skip when `duckdb` is not
on PATH. Override the binary with `QUACK_IT_DUCKDB=/path/to/duckdb`.

The `oracle` profile adds `duckdb_jdbc` as a test-only dependency and runs
the parity suite that compares `ResultSetMetaData`/`getObject` behavior
against DuckDB's own JDBC driver. It is off by default so a plain
`mvn test` needs no native DuckDB JDBC library.

## Design

```
codec/      BinaryReader/Writer — DuckDB BinarySerializer (LE uint16 field ids,
            ULEB128/SLEB128 ints, length-prefixed strings/blobs/lists,
            objects terminated by FIELD_END = 0xFFFF)
type/       Logical type model and codec (full DuckDB type system)
message/    Quack message records, MessageCodec, DataChunk vector decoder
            (FLAT / CONSTANT / DICTIONARY / SEQUENCE encodings)
transport/  QuackUri parser, QuackHttpTransport (POST /quack via JDK HttpClient)
sql/        java.sql.* surface (Driver, Connection, Statement,
            PreparedStatement, ResultSet, ResultSetMetaData, DatabaseMetaData,
            with Skeletal* bases that throw SQLFeatureNotSupportedException
            for the parts we don't yet implement)
```

The `codec/type/message/transport` layers are reusable for an ADBC driver
in Go (or any other language); a companion `quack-adbc` is on the
GizmoData roadmap.

## Compatibility notes

- DataChunk vector encodings supported: **FLAT**, **CONSTANT**,
  **DICTIONARY**, **SEQUENCE**. **FSST** is not yet supported.
- Integer JDBC mappings preserve the logical range: UTINYINT, USMALLINT,
  and UINTEGER use SMALLINT, INTEGER, and BIGINT respectively. UBIGINT,
  HUGEINT, and UHUGEINT use `Types.OTHER` and return `BigInteger` from
  `getObject`, including small values and elements of nested types.
  Use `getObject` or `getBigDecimal` to retain their full range; explicit
  primitive getters can narrow values. Precision reports the actual decimal
  capacity (20/39/39 digits for UBIGINT/HUGEINT/UHUGEINT), deliberately
  differing from native duckdb-jdbc 1.5.5.0's undercounts (19/38/38).
- Nested types (STRUCT / LIST / MAP / ARRAY) are wrapped for JDBC:
  `getObject` returns a `java.sql.Array` for LIST/ARRAY, a `java.sql.Struct`
  for STRUCT, and a `java.util.Map` for MAP, matching DuckDB's own JDBC
  driver. `getColumnTypeName` reports the full element type
  (`INTEGER[]`, `DECIMAL(5,2)[]`, `STRUCT(a INTEGER, b VARCHAR)`,
  `MAP(INTEGER, VARCHAR)`, `ENUM('x', 'y')`, ...).
  - Only the top-level column is wrapped: nested elements *inside* a
    LIST/ARRAY/STRUCT/MAP remain plain `java.util.List` / `java.util.Map`
    values rather than being recursively wrapped as `java.sql.Array` /
    `java.sql.Struct` the way duckdb-jdbc does.
- Unnamed STRUCTs (tuples such as `row(1, 2)`) preserve attributes by position.
  The wire layer and nested values use `java.util.List`; named STRUCTs retain
  their field-name maps. APPEND accepts exact-length lists for unnamed STRUCTs
  and maps for named STRUCTs. Top-level tuples still return `java.sql.Struct`.
- Prepared-statement parameters use client-side literal substitution.
  Native parameter binding will follow once the Quack protocol surfaces
  bind parameters (`PREPARE_REQUEST` currently carries only the SQL text).
  Prepared SQL containing Unicode whitespace that DuckDB normalizes before
  lexing (such as nonbreaking spaces) is rejected to avoid ambiguous marker
  boundaries. Use ASCII whitespace in SQL; parameter values may contain
  those Unicode characters.
- Every real prepared-statement marker must be bound before execution or
  `addBatch()`. Bind SQL NULL explicitly with `setNull` or a supported null
  setter value; an unbound marker is not NULL. Indices are one-based and must
  not exceed the marker count. `clearParameters()` removes current bindings
  without changing queued batches. `getMetaData()` may probe missing values
  as NULL but does not bind them for subsequent execution.
- Statement and prepared-statement batches stop at the first SQL error.
  `BatchUpdateException.getUpdateCounts()` contains only the successful
  execution prefix, including genuine zero-row updates; the failed command
  and unattempted suffix are absent. The submitted batch is cleared after
  success or a SQL execution failure. Counts do not imply commitment: manual
  transactions still require commit or rollback.
- Non-null BIGNUM, TYPE, and AGGREGATE_STATE values are explicitly unsupported
  on decode and APPEND, including nested values. Their physical VARCHAR storage
  is binary, not text. BIGNUM reports `Types.OTHER`; nulls and empty results remain
  usable. Use `CAST(value AS VARCHAR)` in SQL to retrieve BIGNUM as decimal text.
  VARCHAR/CHAR/JSON text and BLOB/BIT/GEOMETRY raw-byte representations are unchanged.
- The `APPEND_REQUEST` fast-path encodes scalar and nested
  (STRUCT / LIST / ARRAY / MAP) DataChunks, so `QuackConnection.session()
  .appendChunk(...)` can bulk-load nested data. Appends through a JDBC
  connection's session honor its auto-commit mode and participate in its
  commit/rollback. Independently created `QuackSession` instances retain
  caller-managed transaction behavior.

### Calendar-aware temporal values

Timestamp APPEND checks the final wire-unit value rather than an intermediate
seconds product. It rejects overflow and infinity-sentinel collisions while
preserving finite signed-min payloads and existing sub-unit truncation.

DATE and timestamp infinities are explicitly unsupported as Java temporal
values. TIME/TIME_NS outside `00:00 <= value < 24:00`, including `24:00`, and
timestamps outside Java's representable range also fail rather than normalize.
Cast such values to VARCHAR in SQL when their textual representation is needed.
Null validity is checked before payload conversion; a signed-min payload with
validity set remains finite where representable. TIMETZ still returns its
existing lossless packed `Long`, not a new OffsetTime representation.

The `Calendar` overloads of `getDate`, `getTime`, `getTimestamp`, `setDate`,
`setTime`, and `setTimestamp` use the supplied timezone for supported temporal
conversions. Zone-less timestamps are interpreted in that timezone on reads;
timestamp instants are projected into local SQL fields on binds. Reading a
`TIMESTAMPTZ` as `Timestamp` always preserves its instant, regardless of Calendar
or server timezone. Binding a `Timestamp` still produces SQL `TIMESTAMP`, not
`TIMESTAMPTZ`; a subsequent server-side cast uses the server session timezone.

Calendar-aware Date reads use local midnight. Time reads use local 1970-01-01
as the date anchor, not the Calendar's current date. The Calendar provides its
actual timezone rules and leniency; SQL fields remain proleptic Gregorian even
with a non-Gregorian Calendar. Lenient reads advance through DST gaps; strict
Calendars reject nonexistent local times with `SQLException`. Ambiguous times
use the later occurrence. Caller-owned Calendars and values are not mutated.

Timestamp reads preserve nanoseconds, while timestamp literals retain the
driver's existing microsecond precision. Calendar-aware Time conversions retain
milliseconds; use `LocalTime` via `getObject` for full fractional precision.
A null Calendar delegates to the existing no-Calendar overload unchanged.
These semantics deliberately differ from native duckdb-jdbc 1.5.5.0's ignored
Date/Time calendars, lost timestamp bind fractions, and shifted TIMESTAMPTZ reads.

## Credits

- Wire-format codec ported clean-room from
  [`@quack-protocol/sdk`](https://github.com/tobilg/quack-protocol) by Tobi (MIT)
- `DatabaseMetaData` queries modeled on
  [`duckdb/duckdb-java`](https://github.com/duckdb/duckdb-java)'s
  `DuckDBDatabaseMetaData` (MIT)
- Many thanks to the DuckDB team for shipping a refreshingly small remote
  protocol

## License

[MIT](LICENSE) — see `LICENSE` for the full text plus attribution.

## Contributing

Issues and PRs welcome at <https://github.com/brikk/duckdb-quack-jdbc>.
See [CLAUDE.md](CLAUDE.md) for contributor notes (layout, conventions,
DBeaver-compat expectations).
