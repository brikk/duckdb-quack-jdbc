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

> **Status:** Experimental / alpha. The Quack protocol itself shipped on
> 2026-05-12 and will stabilize as part of DuckDB v2.0 in September 2026.
> Expect breaking changes between now and then. As of DuckDB v1.5.3
> ("Variegata"), `quack` ships as a **core** extension — no more
> `core_nightly` repository needed. This driver is tested against the
> `quack` extension bundled with DuckDB CLI v1.5.4, including a
> parity suite that checks column-type reporting against DuckDB's own
> JDBC driver (`duckdb_jdbc`) as a behavioral oracle.

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
    <version>0.3.0</version>
</dependency>
```

**Gradle:**

```groovy
implementation "dev.brikk.duckdb:quack-jdbc:0.3.0"
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
| `httpHeader.<Name>`  | (none)  | Extra HTTP header sent with every request (proxy/LB auth). Repeatable. Properties only — rejected on the URL. |

These options can be set on the URL or via `java.util.Properties` passed
to `DriverManager.getConnection`. URL values take precedence. Token
resolution checks `token`, then `password`, then `tokenEnv`, then
`tokenFile`.

`tokenEnv` and `tokenFile` are the exception: they are only accepted via
`Properties` (or a tool's driver-properties panel) and are rejected if
they appear on the URL. A pasted or shared URL must not be able to read
a local secret and send it to whatever host the URL names.

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
            return new QuackHttpTransport(uri.httpUri(), httpClient, uri.requestTimeout());
        })) {
    // use the connection normally
}
```

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
- The `APPEND_REQUEST` fast-path encodes scalar and nested
  (STRUCT / LIST / ARRAY / MAP) DataChunks, so `QuackConnection.session()
  .appendChunk(...)` can bulk-load nested data. Appends through a JDBC
  connection's session honor its auto-commit mode and participate in its
  commit/rollback. Independently created `QuackSession` instances retain
  caller-managed transaction behavior.

### Calendar-aware temporal values

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
