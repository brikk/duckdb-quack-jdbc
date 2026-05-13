# Changelog

All notable changes to **quack-jdbc** are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
