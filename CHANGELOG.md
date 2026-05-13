# Changelog

All notable changes to **quack-jdbc** are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — JDBC coverage parity with DuckDB's own driver

Closed the eight method-coverage gaps surfaced by a side-by-side audit
against `org.duckdb.DuckDB*` (full audit at `/tmp/jdbc-coverage-audit.md`
during development). All eight are required by at least one of
DBeaver / IntelliJ DataGrip / dbt / Spark JDBC / HikariCP.

- **`PreparedStatement.getMetaData()`** — returns
  {@link java.sql.ResultSetMetaData} for the prepared query by running
  `SELECT * FROM (<sql>) LIMIT 0` with NULL-filled placeholders.
  Returns {@code null} for non-SELECT statements per JDBC contract.
  Required by `spark.read.jdbc(...)` for schema inference.
- **`PreparedStatement.getParameterMetaData()`** — returns a
  `QuackParameterMetaData` reporting the `?`-marker count (counted
  outside single-quoted strings and double-quoted identifiers). Used
  by Hibernate / Spring JDBC / DataGrip parameter inspectors.
- **`Statement.addBatch(String)` / `clearBatch()` / `executeBatch()`**
  and **`PreparedStatement.addBatch()` / `executeBatch()`** — executed
  as a sequential loop (no native batch protocol exists in Quack today).
  Throws `BatchUpdateException` on individual failures with the partial
  counts array. Required by `dbt seed` and Spark `df.write.jdbc(...)`.
- **`ResultSet.getArray(...)`** — wraps the decoded {@code List<Object>}
  in a {@code QuackArray} (`java.sql.Array`) carrying the element's
  logical type for `getBaseType` / `getBaseTypeName`. Required by
  DBeaver's value editor for LIST / ARRAY columns.
- **`ResultSet.getBlob(...)`** — wraps the decoded {@code byte[]} in a
  {@code QuackBlob} (`java.sql.Blob`). Required by DBeaver's BLOB value
  editor.
- **`Connection.createArrayOf` / `createStruct`** — return opaque
  `QuackArray` / `QuackStruct` wrappers usable in `setObject(_, Array)`
  / `setObject(_, Struct)`. Used by dbt IN-list macros and adapter
  frameworks.
- **`Connection.setCatalog` / `setSchema`** — now emit
  `USE "catalog"."schema"` instead of silently storing a field. DBeaver
  catalog-navigator switching actually changes context server-side.
- **`Connection.isValid(int)`** — now actually runs `SELECT 1` to detect
  dead server-side connections instead of only checking the local
  `closed` flag. Required by HikariCP / pgbouncer-style pool
  health-check semantics.
- **`Connection.setTypeMap(Map)`** — silently accepts `null` and empty
  maps (the call HikariCP makes during eviction), throws only for
  non-empty mappings.
- **`Statement.cancel()`** — degraded from `SQLFeatureNotSupportedException`
  to a best-effort no-op so DBeaver / DataGrip query-timeout buttons
  don't crash the UI. Real protocol cancel will follow when Quack
  surfaces it.

Plus 15 new integration tests exercising every fix end-to-end against
a live DuckDB+Quack server (52 tests total, all green).

### Fixed
- `QuackHttpTransport` now iterates every address returned by
  `InetAddress.getAllByName(host)` instead of relying on JDK
  `HttpClient`'s first-address behavior. Hosts like `localhost` that
  resolve to both `127.0.0.1` and `::1` now succeed against a server
  bound to either family — previously a `ConnectException` on the first
  address (IPv4 by default on macOS) aborted the whole request even
  though an IPv6 listener was reachable.
- Error messages no longer say `Quack HTTP request failed: null` when
  the cause has no message; the exception class name is used as a
  fallback. The exhausted-addresses error names every address that was
  tried, including the underlying failure detail.

### Added
- First cut of the JDBC driver for DuckDB's Quack remote protocol.
- `BinaryReader` / `BinaryWriter` for DuckDB's BinarySerializer wire format
  (little-endian uint16 field ids, ULEB128/SLEB128, fixed-width primitives,
  length-prefixed strings/blobs/lists, nested objects terminated by
  `FIELD_END = 0xFFFF`).
- Logical type model and codec covering BOOLEAN, integer family
  (TINYINT…HUGEINT including unsigned), FLOAT/DOUBLE, DECIMAL, VARCHAR/CHAR,
  BLOB/BIT/GEOMETRY, DATE, TIME / TIME_NS / TIME_TZ, TIMESTAMP variants
  (SEC / MS / default µs / NS / TZ), INTERVAL, UUID, ENUM, STRUCT, LIST,
  MAP, ARRAY, plus all `ExtraTypeInfo` variants.
- `DataChunk` decoder supporting **FLAT**, **CONSTANT**, **DICTIONARY**,
  and **SEQUENCE** vector encodings with validity bitmaps. FSST is not
  yet supported.
- Quack protocol message records and `MessageCodec` for `CONNECTION_*`,
  `PREPARE_*`, `FETCH_*`, `APPEND_REQUEST`, `SUCCESS_RESPONSE`,
  `DISCONNECT_MESSAGE`, and `ERROR_RESPONSE`.
- `QuackHttpTransport` over `java.net.http.HttpClient` (JDK 17+).
- JDBC URL parser accepting `jdbc:quack://host[:port][/database][?token=…&tls=…]`.
- `QuackDriver` (auto-registered via `META-INF/services`), `QuackConnection`,
  `QuackStatement`, `QuackPreparedStatement` (client-side `?` interpolation),
  `QuackResultSet`, `QuackResultSetMetaData`.
- `QuackDatabaseMetaData` modeled directly on DuckDB's own JDBC driver so
  DBeaver and other tools that introspect via `getTables` / `getColumns` /
  `getPrimaryKeys` / `getImportedKeys` / `getExportedKeys` / `getIndexInfo` /
  `getTypeInfo` / `getFunctions` see the same shape they would from a
  native DuckDB connection.
- JUnit 5 integration suite that spawns a real `duckdb -unsigned` process,
  installs the Quack extension from `core_nightly`, calls `quack_serve` on
  a random local port, and exercises the driver end-to-end (connect,
  CRUD, multi-chunk fetch, scalar type round-trips, DatabaseMetaData,
  bad-token auth, concurrent connections).
- Unit test coverage for the BinarySerializer round-trip, URI parsing,
  and message encode/decode.

### Pinned versions
- DuckDB CLI: 1.5.2+ (tested with 1.5.2)
- Quack extension: `duckdb/duckdb-quack@daae4826f57986fbb6cc2116316f89c673814b23`
  (2026-05-10, current `main` — no release tags exist yet at the time of
  writing; will be retargeted as the protocol stabilizes for DuckDB 2.0
  in September 2026)

### Known limitations
- The Quack protocol is beta; breaking changes are expected before DuckDB 2.0.
- `PreparedStatement` parameter binding uses client-side literal
  substitution — the protocol's `PREPARE_REQUEST` does not (yet) carry
  bind parameters.
- `APPEND_REQUEST` (vector encoding) is decoder-complete but not yet
  encoder-complete; the driver does not yet expose the append fast-path.
- FSST-compressed vectors and the TIME WITH TIME ZONE wall-clock decode
  are not yet supported.
- Nested types (STRUCT/LIST/MAP/ARRAY) decode to plain Java collections;
  full `java.sql.Array` / `java.sql.Struct` wrapping is on the roadmap.

## [0.1.0] — _planned_

First public release will be tagged once integration tests have been
exercised against a production-deployed Quack server.
