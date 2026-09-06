# Quack JDBC Whole-Repository Review

Reviewed 2026-09-05 at `/home/jayson/DEV/brikk/fork-quack-jdbc`, version `0.7.0-SNAPSHOT`.

This reviews the original `0.7.0-SNAPSHOT` implementation, including inherited code, not only changes introduced by the fork. The report was subsequently moved into `CODE_REVIEW.md` and given stable identifiers. Implementation passes resolve B1-B12, B16, B18, B19, B22, B29-B32, B39, B40, B41, L4, I1, and I3; resolution notes and V5-V20 record the scope and verification. B15 is reclassified as a stock-protocol limitation: adding authoritative result metadata is upstream feature work, not an existing server feature or a driver-release prerequisite. The custom server experiment was withdrawn before publication. Stock signed Quack remains supported without special connection options. Earlier commit history and verification records remain available; unrelated CLAUDE.md and DUCKDB_COMPATIBILITY.md changes were preserved.

All source references below are relative to the repository root. `sql/`, `message/`, `codec/`, `type/`, and `transport/` abbreviate directories under `src/main/java/com/gizmodata/quack/jdbc/`. Original finding line numbers refer to the reviewed baseline; subsequent edits may shift them. Keep the original evidence alongside resolution notes.

## Stable Identifiers

Use these IDs in requests, changes, tests, and follow-up discussions, for example: "do B1", "investigate L3", or "implement I1 and I2".

| Prefix | IDs | Meaning |
| --- | --- | --- |
| B | B1-B41 | Prioritized correctness findings |
| L | L1-L6 | Lower-priority issues, cleanup, and operational tradeoffs |
| C | C1-C4 | Native-parity observations requiring a compatibility decision before changes |
| I | I1-I9 | Build, testing, and engineering improvements |
| S | S1-S8 | Strengths to preserve, not implementation tasks |
| V | V1-V20 | Verification evidence and limitations, not implementation tasks |

IDs are permanent and independent of severity, priority rank, and document order. B1-B40 match the original full report's numbered findings, not the shorter chat summary's numbering. Never renumber or reuse an ID; record resolution under the existing item and append new IDs for new findings. If an item needs separate work units, retain its parent ID and introduce suffixes such as B4a and B4b.

"Do B1" means address that finding, add targeted regression tests, run relevant verification, and record the outcome under B1. Related IDs are not automatically included in the request. C items and explicit protocol tradeoffs require a decision or investigation rather than an assumed behavior change. Items without an explicit resolution remain open.

## High-Priority Findings

P1 means a fix should precede broader production use: executable SQL escaping parameter boundaries, silently changed data, broken transaction expectations, ordinary supported queries failing, or unbounded resource failures.

### B1. Prepared parameter substitution can expose executable SQL [P1]

Location: `sql/QuackPreparedStatement.java:58-100`.

The two scanners understand single and double quotes but not SQL comments, escape strings, or dollar quoting. This valid SQL has one real parameter: `SELECT 1 /* ? ' */ WHERE ? = 0`. Binding the string `*/ UNION ALL SELECT 999 --` substitutes into the block comment, and execution returns rows 1 and 999. Quotes placed inside a comment do not protect the parameter value from a comment terminator. Separately, `SELECT ? AS value -- ?` incorrectly requires two bindings.

Use a single DuckDB-aware lexical scanner for counting and substitution. Handle line comments, nested block comments, ordinary/escape strings, quoted identifiers, and dollar-quoted strings. Native binding is preferable when the supported protocol provides it, but the current literal-substitution path must be safe independently. Confirmed against DuckDB 1.5.5.

**Resolution (2026-09-05): resolved in the working tree.** The statement scans marker positions once and reuses them for metadata, execution, and batches. The scanner handles nested/line comments, doubled quotes, E strings and their newline continuations, dollar tags, and identifier boundaries. Inserted expressions have block-comment token boundaries; negative numbers are parenthesized to preserve cast/operator precedence.

DuckDB also normalizes certain Unicode whitespace before lexing, using different quoting rules. Prepared SQL templates containing those characters are now explicitly rejected with SQLException via Connection.prepareStatement. Such text can still be bound as data: SqlLiteral uses an ASCII-only UTF-8 decoding expression for affected values, preserving them even after escape strings or comments containing quotes. This deliberate restriction is documented in README. Tests: QuackPreparedStatementTest, SqlLiteralTest, and SqlLiteralRoundTripIntegrationTest, including the original injection, Unicode preprocessor cases, exact bound Unicode values, and negative-number precedence. See V5.

### B2. UBIGINT values are decoded as signed Long [P1]

Location: `message/VectorCodec.java:485-488,512-519`; `message/DecodedVector.java:105-110`.

`SELECT 18446744073709551615::UBIGINT` returns `-1` through `getObject()`. UINT64 uses the signed `LongVec` path without reconstructing the unsigned logical value. Materialize UBIGINT as a nonnegative BigInteger and update metadata consistently. Cover the 2^63 and 2^64 boundaries across compressed and nested vectors. Confirmed live on 1.5.5.

**Resolution (2026-09-05): resolved in the working tree.** UBIGINT uses ObjectVec with nonnegative BigInteger values for every non-null row, including zero and values below 2^63. FLAT and SEQUENCE decoding reconstruct unsigned values from the raw 64-bit word; CONSTANT and DICTIONARY retain the object representation. The low-level BinaryReader raw-bit contract and explicit primitive getter narrowing are unchanged.

Tests: VectorEncodingDecodeTest uses independent FLAT/CONSTANT/DICTIONARY/SEQUENCE fixtures around 2^63 and 2^64, nulls, and empty vectors. AppendIntegrationTest verifies exact APPEND and JDBC object/decimal/string retrieval. NestedReadEdgeIntegrationTest covers lists, fixed arrays, named structs, maps, and nested lists. StreamingIntegrationTest reads 100,000 high-bit values across actual FETCH batches. OracleParityIntegrationTest compares scalar and array values/classes with native JDBC. See V6.

### B3. Numeric metadata directs generic readers into undersized getters [P1]

Location: `sql/JdbcTypeMap.java:18-21`.

UTINYINT/USMALLINT/UINTEGER are advertised as TINYINT/SMALLINT/INTEGER, whereas DuckDB JDBC promotes them to SMALLINT/INTEGER/BIGINT. A metadata-driven reader of `255::UTINYINT` using getByte obtains -1. HUGEINT/UHUGEINT also advertise BIGINT despite requiring more than 64 bits; native DuckDB uses OTHER. Correct the mappings, precision, and class information together. This is separate from the UBIGINT decoder defect and was verified against native 1.5.5.0.

**Resolution (2026-09-05): resolved in the working tree.** UTINYINT/USMALLINT/UINTEGER now report SMALLINT/INTEGER/BIGINT with Short/Integer/Long Java classes. UBIGINT/HUGEINT/UHUGEINT report OTHER and BigInteger. Array base metadata uses the same mappings while retaining the original logical type name. Unsigned display widths and precision describe the full logical range.

Native JDBC 1.5.5.0 was checked directly and by tagged source. Type codes, Java classes, scalar signedness, and values match native behavior. Precision deliberately uses actual decimal digit capacities: 20/39/39 for UBIGINT/HUGEINT/UHUGEINT instead of native's underreported 19/38/38. Native's zero display widths are not copied. Tests: JdbcTypeMapTest covers all ten integer logical types and array base metadata; OracleParityIntegrationTest exercises signed/unsigned endpoints, zero, nulls, metadata-driven getters, and variable/fixed arrays. Non-numeric metadata defects under B23 remain open. See V6.

### B4. Exact numeric conversions pass through floating point or long [P1]

Location: `sql/QuackResultSet.java:200-206,324-327`; `message/VectorCodec.java:964-975`.

Reading BIGINT 9007199254740993 with getBigDecimal returns 9007199254740992. Encoding the same Long or BigInteger into DECIMAL(18,0) loses the same unit. Both paths unnecessarily pass exact integers through doubleValue. Additionally, converting the string `18446744073709551616` through `getObject(..., BigInteger.class)` returns zero because the intermediate BigDecimal is narrowed to long. Preserve exact integral values and construct arbitrary-precision values without lossy intermediates. All examples reproduced.

**Resolution (2026-09-05): resolved in the working tree.** Integral JDBC values and decimal APPEND inputs no longer pass through double. Typed BigInteger retrieval converts decimal strings without narrowing to long and preserves the exact represented value of finite Float/Double inputs before truncating the fraction; non-finite inputs produce SQLException. Tests: QuackResultSetConversionTest, VectorCodecRoundTripTest, AppendIntegrationTest, and SqlLiteralRoundTripIntegrationTest cover values above 2^53/2^64, signed long endpoints, decimal scales, floating-point powers of two, and nulls. The separate B2 and B6 defects were subsequently resolved in the second approved pass. See V5-V6.

### B5. APPEND encoding silently overflows logical numeric ranges [P1]

Location: `message/VectorCodec.java:864-912,964-975`.

Appending BigDecimal 40000 into DECIMAL(4,0) succeeds and stores -25536. The INT16 decimal path checks only intValueExact and then emits two bytes; declared precision is never enforced. Other local examples include TINYINT 128 becoming -128 and UHUGEINT -1 becoming 2^128-1. Validate logical signed/unsigned ranges and DECIMAL precision before serialization; matching a Java storage width is insufficient. DECIMAL corruption confirmed against 1.5.5.

**Resolution (2026-09-05): resolved in the working tree.** APPEND encoding checks signed/unsigned integer ranges before narrowing, rejects fractional/non-finite integer inputs, validates DECIMAL width/scale, and checks decimal precision after rounding. Float/Double integer inputs are checked using their exact binary value, not their rounded display string. Tests: VectorCodecRoundTripTest exercises all integer widths, independent wire-bit assertions, floating boundaries, decimal width transitions, invalid metadata, and rounding carry. AppendIntegrationTest verifies an invalid two-row decimal append writes no rows and valid boundary values still round-trip. B6 was separately resolved in the second approved pass, removing the signed-128 exclusions from these tests. See V5-V6.

### B6. Valid signed HUGEINT values cannot be encoded [P1]

Location: `codec/HugeIntParts.java:10-17`; called by `message/VectorCodec.java:892-902`.

The lower unsigned 64-bit word is converted using longValueExact. This rejects valid bit patterns with bit 63 set: HUGEINT -1, 9223372036854775808, and 18446744073709551615 all throw ArithmeticException. Validate the overall signed 128-bit range and preserve the low word's bits with longValue rather than requiring its magnitude to fit a signed long. This also affects high-precision decimals. Confirmed with local encoder probes.

**Resolution (2026-09-05): resolved in the working tree.** HugeIntParts.ofSigned preserves the low 64 bits with longValue and converts the arithmetic-shifted upper word with longValueExact. This accepts the entire signed-128 range and rejects values outside it instead of wrapping modulo 2^128. BinarySerializerTest first reproduced the previous failure and now covers -1, both signed endpoints, low-word boundaries, and out-of-range values. VectorCodecRoundTripTest no longer excludes valid HUGEINT maxima or wide DECIMAL boundaries. AppendIntegrationTest verifies exact server-side HUGEINT values and positive/negative DECIMAL(19/38,0/2) endpoints, including nulls. See V6.

### B7. Unnamed STRUCT fields overwrite each other [P1]

Location: `message/VectorCodec.java:361-365,749-762`; `sql/QuackResultSet.java:262-273`.

DuckDB tuples have empty field names. Storing their values in a map keyed by field name collapses distinct positions. `SELECT row(1,2)` returns JDBC Struct attributes `[2,2]`; a tuple inside a list loses the first attribute altogether. Preserve positional values for unnamed structs in both the wire layer and JDBC wrappers. Confirmed against 1.5.5.

**Resolution (2026-09-05): resolved.** STRUCTs containing unnamed fields use positional lists in the wire layer, including nested values and APPEND inputs. Named STRUCTs retain their shipped map representation. APPEND rejects ambiguous maps and wrong-length lists for tuples; top-level JDBC retrieval wraps positional values as Struct. Tuple type names match native JDBC. Recursive nested JDBC wrapping remains intentionally unsupported. Decoder child counts are checked before materialization so malformed tuples cannot silently lose attributes.

Tests: independent FLAT/CONSTANT/DICTIONARY fixtures, missing/extra child fixtures for named and unnamed STRUCTs, encoder validation and null round-trips, live scalar/list/fixed-array/STRUCT/MAP nesting, typed Struct getters and wasNull, 100,000 streamed tuples across FETCH batches, and native scalar value/type-name parity. DuckDB cannot persist unnamed STRUCT fields directly; the live APPEND test casts positional tuple inputs into named destination STRUCTs and checks scalar field values on the server. See V9.

### B8. NULL temporal values are converted before checking validity [P1]

Location: `message/VectorCodec.java:439-442`.

`SELECT NULL::TIME`, `NULL::TIME_NS`, and `NULL::TIMESTAMP_S` fail. The decoder attempts logical conversion of the signed null sentinel before replacing it with null; multiplication or temporal construction throws. Consume the fixed-width slot without logical conversion when invalid. Cover mixed null/non-null rows and nested/compressed vectors with server-produced null bytes. Self-round-trips miss this because the local encoder writes zero into null slots. Confirmed against 1.5.5.

**Resolution (2026-09-05): resolved.** The fixed-width object decoder now checks validity before logical conversion. Invalid slots consume exactly their physical byte width without interpreting or allocating a value, preserving alignment for following rows. Valid values and the existing fixed-size payload-length checks are unchanged. No public API or new reader helper was added.

Tests: VectorEncodingDecodeTest uses independent signed-min sentinel fixtures across 18 fixed-width logical types, physical widths 1/2/4/8/16, and FLAT/CONSTANT/DICTIONARY encodings. It also verifies that truncated all-null payloads and invalid non-null ENUM values remain rejected. NestedReadEdgeIntegrationTest covers null temporal scalars, mixed rows, JDBC wasNull/getter behavior, lists, fixed arrays, named structs, null structs, maps, and nested lists. StreamingIntegrationTest checks 100,000 alternating null/non-null rows for each of TIME, TIME_NS, and TIMESTAMP_S across actual FETCH batches. The new regressions reproduced the original errors before the fix. See V7. Calendar semantics (B11), temporal boundaries (B29), and special temporal values (B30) remain separate work.

### B9. The documented bulk append API bypasses manual transactions [P1]

Location: `sql/QuackSession.java:123-140`; `sql/QuackConnection.java:100-106`.

After setAutoCommit(false), an append through connection.session().appendChunk as the first operation executes in server autocommit mode. rollback then leaves the appended row in the table. Lazy BEGIN is called only on the statement path. Make every connection-owned execution path transaction-aware or expose a safe connection-level append API and clearly separate low-level session semantics. Test first append after disabling autocommit and after commit/rollback. Confirmed against 1.5.5.

**Resolution (2026-09-05): resolved for the documented APPEND path in the working tree.** JDBC-created sessions retain their owning connection and invoke its existing lazy transaction initialization before APPEND. Standalone sessions remain caller-managed and public session constructors are preserved. A failed BEGIN prevents APPEND and can be retried. Tests: QuackConnectionTransactionTest checks request ordering, standalone sessions, and failed BEGIN; TransactionIntegrationTest checks visibility from another connection, rollback, commit, first append after transaction completion, and enabling autocommit. Direct low-level cursor execution was not expanded into JDBC transaction management. See V5.

### B10. A database named in the URL is cached but never selected [P1]

Location: `sql/QuackConnection.java:26-33,144-151`.

Connecting to `/review_other` reports that catalog from getCatalog while current_database remains memory. Calling setCatalog with the same name cannot repair it because the cache suppresses USE. Unqualified writes can target the wrong database. Select and validate the requested catalog before caching it, or explicitly reject the currently nonfunctional URL option. The README labels it reserved but also says it is passed through; neither the handshake nor an initialization query uses it. Confirmed against 1.5.5 with an attached catalog.

**Resolution (2026-09-05): resolved in the working tree.** Connection initialization issues `USE "requested_catalog"."main"` before caching the requested catalog. The two-part name prevents an existing schema from masquerading as a catalog. Failure disconnects the newly opened session. Tests: QuackDriverCustomTransportTest verifies initialization order, quoting, cache behavior, and disconnect on failure; JdbcCoverageIntegrationTest verifies actual unqualified writes, catalog names containing spaces/quotes, nonexistent catalogs, and collisions with existing schema names. README now describes the active URL behavior. See V5.

### B11. Calendar-aware timestamp methods silently ignore time zones [P1]

Location: `sql/SkeletalResultSet.java:113-118`; `sql/QuackPreparedStatement.java:191-196`.

With JVM zone UTC, retrieving timestamp `2024-01-02 03:04:05.123456` using an America/Los_Angeles Calendar yields 03:04:05Z instead of the 11:04:05Z returned by native JDBC. Binding instant 2026-01-01T00:00Z with a GMT+09 Calendar likewise stores 00:00 instead of 09:00. Implement the requested calendar interpretation and preserve fractional precision. Date/Time overloads also discard the argument; review their contracts separately rather than assuming native behavior is perfect. Timestamp examples confirmed.

**Resolution (2026-09-05): resolved.** QuackResultSet's Calendar overloads now interpret decoded local fields directly, without a lossy conversion through the JVM default timezone; label overloads delegate to the indexed versions. QuackPreparedStatement's Calendar setters project the input instant into local SQL fields at bind time. A package-private CalendarConversion helper shares the inverse conversions and honors actual TimeZone rules, including custom IDs and modified offsets. Calendar timezone and leniency apply to SQL's proleptic Gregorian fields, not the caller's calendar system or Gregorian cutover. Caller-owned values and Calendars are not mutated; null Calendars retain the existing no-Calendar behavior.

Timestamp reads preserve nanoseconds, including negative epochs; binds retain the existing SQL TIMESTAMP microsecond precision without a new millisecond truncation. TIMESTAMPTZ-to-Timestamp reads preserve the stored instant. Date reads construct local midnight; Time reads anchor local fields to 1970-01-01 and retain representable milliseconds. Lenient Calendar reads advance through DST gaps; non-lenient Calendars reject nonexistent local times with SQLException; overlaps use the later occurrence. String parsing rejects invalid fields instead of normalizing them before Calendar validation. General no-Calendar conversion behavior, nanosecond binding expansion, B29/B30 boundary/special-value handling, and B35 getTime(TIMESTAMPTZ) remain outside this pass.

Tests: eight new QuackResultSetConversionTest methods, five CalendarTemporalIntegrationTest methods, and one OracleParityIntegrationTest method cover index/label access, nulls/wasNull, three JVM zones, two server zones, DST gaps/overlaps and non-hour transitions, custom TimeZone rules, non-Gregorian Calendars, pre-cutover round trips, negative epochs, timestamp units/fractions, input immutability, and bind-time snapshots. Native parity is asserted for the original ordinary timestamp examples. Deliberately not copied: native 1.5.5.0's ignored Date/Time Calendars, millisecond-only Calendar timestamp binds, and shifted TIMESTAMPTZ reads. Explicit expected instants establish those contracts. See V8 and README's Calendar-aware temporal guidance.

### B12. Small malformed inputs can trigger JVM resource failures [P1]

Location: `codec/BinaryReader.java:182-188,265-269`; `message/VectorCodec.java:95-104`.

List lengths directly become allocation capacities, bounds checks use overflowing offset+length arithmetic, and recursive compressed vectors have no nesting budget. In a heap-bounded local process, a five-byte maximum list length caused OutOfMemoryError, an overflowed byte-range check reached an impossible allocation, and about 60 KB of repeated CONSTANT headers caused StackOverflowError. Use remaining-length checks, checked arithmetic, allocation/decompressed-size budgets, and recursion limits. The HTTP response body is also buffered without a size cap, so harden that boundary too. These are local malformed-input probes, not claims about exploitation of a deployed server.

**Resolution (2026-09-05): resolved with B32.** DecodeLimits defaults to 64 MiB response bodies, 256 MiB decoded allocation accounting, and 64 active nesting frames. BinaryReader uses remaining-length checks and shares allocation/depth state across payload subreaders. Metadata objects, collections, strings, vector materialization, compressed expansion, and nested slices consume the same per-message budget before allocation. Wire objects and inline CONSTANT/DICTIONARY recursion both enforce depth. Limits are configurable through connection properties and additive low-level overloads; existing signatures retain defaults.

The HTTP transport incrementally reads bounded bodies, rejects oversized declared lengths early, checks actual bytes even without a length, and closes bodies on all paths. Body read/size/close/decode failures cannot replay the POST through address fallback. These limits are conservative accounting, not measured JVM heap or a global concurrency/retained-result cap; valid oversized messages may require tuning. Outbound application-generated data is not budgeted. B13/B14 deadlines and cancellation remain open. See README and V10.

**Compatibility follow-up:** Downstream QK01/QK02 later exposed overly conservative fixed-scalar charges under these defaults. B41/V18 corrects the accounting without raising limits or removing the B12 safeguards.

### B41. Default scalar allocation accounting rejects ordinary wide results [P1]

Downstream IDs: **QK01 and QK02**. Introduced by B12 in `message/VectorCodec.java`, not present in the original review baseline. Published `0.7.0-20260906.014938-10` charges 1,024 bytes for every fixed-object FLAT/SEQUENCE slot, including nulls. With the default 12-chunk fetch batch, a valid 65,537-row projection of four DECIMAL widths plus fixed arrays exceeds the 256 MiB cumulative budget before returning a ResultSet. Downstream comparison confirms QK01 passes on 0.6.0. Two wide null/mixed-temporal queries also exhaust the snapshot budget, but fail on 0.6.0 for the older null-decoding defect, so QK02 is not classified as two additional compatibility regressions.

**Resolution (2026-09-06): corrected in the candidate, with default limits unchanged.** Structural vector allowances remain 128 + 32 bytes per row. Non-null FLAT scalar conversions reserve type-specific cumulative costs immediately before conversion; masked null slots reserve no nonexistent scalar object. SEQUENCE precharges its actual int64 conversion path, including the cheaper long-based wide-DECIMAL conversion. CONSTANT/DICTIONARY continue to charge structural projection storage and all materialized source entries, without charging referenced scalar objects repeatedly. Shared per-message accounting, no-refund semantics, wire/depth limits, checked shapes/arithmetic, nested-container charges, and variable-width charges are preserved.

Scalar allowances include bounded conversion temporaries: 32 bytes for simple date/time/packed-time/UUID/INTERVAL objects, 64 for FLAT ENUM lookup, 160 for long-based DECIMAL, 640 for INT128 DECIMAL, 512 for UBIGINT, 576 for signed INT128, 768 for unsigned INT128, and 384 for timestamps. These are conservative policy estimates informed by JDK 17/21 allocation probes with compressed and uncompressed references, not portable JVM heap guarantees. See V18 for exact reproductions and candidate qualification. The already-published build 10 remains affected until replaced.

## Additional Correctness Findings

### B13. JDBC deadlines and cancellation do not enforce their contracts [P2]

Location: `sql/SkeletalStatement.java:21-22`; `sql/QuackStatement.java:55-60`; `sql/SkeletalConnection.java:66-73,89-90`.

setQueryTimeout, setNetworkTimeout, and cancel are no-ops; isValid ignores its timeout. A delayed transport completes despite all these controls. Pools and GUI tools cannot rely on their configured deadlines. Implement supported deadlines and validate arguments. Cancellation is deliberately documented as a no-op for GUI compatibility; revisit that tradeoff explicitly instead of treating a nonthrowing cancellation test as evidence that cancellation works. Where unsupported, communicate that honestly rather than silently accepting an ineffective control.

### B14. HTTP requestTimeout does not bound response-body consumption [P2]

Location: `transport/QuackHttpTransport.java:95-110`.

On the current JDK 21.0.2, a local server sends headers and one byte promptly, then delays the rest of its response. With requestTimeout=500 ms, send successfully completes after about 1576 ms. HttpRequest.timeout alone does not enforce the whole operation's deadline in this scenario. Apply a deadline that covers body consumption and cancellation/cleanup of the underlying request. This is distinct from the ignored JDBC deadline methods. Exact behavior on JDK 17 was not separately measured.

### B15. Result-kind metadata is unavailable in stock protocol [Protocol Limitation]

Location: `sql/QuackStatement.java:77-96,106-117`.

`execute("SELECT 42::BIGINT AS Count")` reports an update count of 42 and discards the query result. CREATE TABLE instead reports a ResultSet and update count -1. executeUpdate accepts ordinary SELECT and reports zero. Use reliable statement/result-kind information rather than column names, distinguish no-result DDL, and reject query results in executeUpdate. Confirmed live.

**Reclassification (2026-09-06): documented protocol limitation, not a requirement to invent a server feature.** The original recommendation assumed authoritative result-kind information could be obtained. Stock Quack v1 does not carry it. Adding it would be a separate upstream feature; requiring users to install an unofficial extension is not an acceptable driver fix or release prerequisite. The custom implementation was removed before publication. Existing heuristic behavior and its limitations remain; B15 is not claimed fixed. No private capability or special compatibility mode is required. See V20.

### B16. Re-execution leaves previous results open and exposes stale state [P2]

Location: `sql/QuackStatement.java:83-103`.

Executing two queries on a statement leaves the old ResultSet open and readable. Failed re-execution leaves getResultSet pointing to the previous query; a successful update can discard the reference without closing it. Close/reset previous state before any new execution, including before transaction initialization can fail. Confirmed live.

**Resolution (2026-09-06): resolved.** Shared execution-state reset closes the previous ResultSet and clears the update count before query execution, lazy BEGIN, prepared interpolation, or batch execution. Parameter setters and addBatch do not close active results. Targeted QuackStatementExecutionTest verification passed 18 cases, including query/update transitions, failed BEGIN, missing bindings, rendering errors, empty batches, and close/getMoreResults behavior. Independent review found no scoped B16 issues. B15 remains a documented stock-protocol limitation.

### B17. Connection close and commit do not manage dependent resources [P2]

Location: `sql/QuackConnection.java:76-89,118-125`; `sql/SkeletalConnection.java:56-57`.

After closing a connection, its statements and results still report open. After commit, results remain readable despite advertised CLOSE_CURSORS_AT_COMMIT. The connection never tracks its statements. Implement lifecycle ownership and consistent holdability. Apply it when enabling autocommit too. Confirmed live.

### B18. Batch errors report unexecuted commands as successful [P2]

Location: `sql/QuackStatement.java:38-47`; `sql/QuackPreparedStatement.java:138-146`.

A three-entry batch with a duplicate-key error in the middle throws counts `[1, EXECUTE_FAILED, 0]`. The last command was never attempted but zero indicates a successful zero-row update. With stop-on-error behavior, return the successful prefix, or continue all commands and accurately populate every status. Test errors at each position in both batch implementations. Confirmed live.

**Resolution (2026-09-05): resolved.** Both batch implementations retain stop-on-error execution and copy only the successful prefix into BatchUpdateException. The failed command and unattempted suffix have no entries; genuine zero-row successes are retained. The original message, SQL state, vendor code, and SQLException cause are preserved. Batch queues are cleared on caught SQL execution errors and on success. Prepared executeBatch now performs the same initial open-state check as Statement, including for empty batches. Counts describe execution, not commitment, and do not change manual rollback semantics. See V14.

### B19. Missing bindings become NULL; extra parameter indices are accepted [P2]

Location: `sql/QuackPreparedStatement.java:50-55,71-75`; `sql/QuackParameterMetaData.java:23-30`.

Binding only parameter 2 in `SELECT ?, ?` fills parameter 1 with null rather than rejecting an incomplete bind. Indices beyond markerCount are accepted and ignored. Metadata accepts index zero. Separate unbound from bound-null, check bounds at each setter/metadata operation, and validate before execution or batch submission. Confirmed live.

**Resolution (2026-09-05): resolved.** A private unbound sentinel distinguishes missing bindings from explicit NULL in a fixed-size parameter list. Complete binding validation precedes execution (including lazy BEGIN) and addBatch. Setter indices and all eight indexed ParameterMetaData methods enforce 1..markerCount; supported stream/array setters check before consuming their inputs. clearParameters restores unbound state without altering queued snapshots. Metadata probes replace unbound values only in a copy, preserving the existing ability to inspect an incompletely bound SELECT. Type/scale and array rendering findings B20/B21, stream-length validation B26, and batch failure counts B18 remain separate. See V13.

### B20. Typed setters discard SQL types and requested scale [P2]

Location: `sql/QuackPreparedStatement.java:179-201`.

setObject with string "42" and Types.INTEGER still yields VARCHAR; setNull with Types.VARCHAR yields untyped NULL; setting BigDecimal 1.239 as DECIMAL with scale 2 leaves 1.239. This affects casts, overload selection, metadata, and stored values. Retain type/scale with each binding, render typed literals or convert explicitly, and reject unsupported conversions. Confirmed live.

### B21. setArray binds Java identity text, not array values [P2]

Location: `sql/QuackPreparedStatement.java:218`; `sql/SqlLiteral.java:62`; related `sql/SkeletalConnection.java:79-83`.

Binding createArrayOf("INTEGER", [1,2]) through setArray returns a string like `[Ljava.lang.Object;@...` from SELECT ?. getArray produces Object[] and SqlLiteral falls through to toString. createArrayOf also discards the requested element type. Implement typed array literals, including empty/null cases, or explicitly reject this binding. Confirmed live.

### B22. Lazy FETCH errors escape SQLException and invalidate row state incorrectly [P2]

Location: `sql/QuackResultSet.java:61-73,127-131`.

An injected error fetching the next page escapes next as QuackServerException, whereas initial execution translates it to SQLException. The failed advance leaves the previous chunk with an out-of-range row index; a subsequent getter throws ArrayIndexOutOfBoundsException. Translate at the JDBC boundary and mark the current row invalid on failed advancement. Confirmed with a local transport fault.

**Resolution (2026-09-06): resolved.** next invalidates row state before fetching, translates runtime fetch failures to SQLException with the original cause, closes the local cursor, and exhausts the result without retrying a potentially advanced server cursor. The ResultSet remains open until explicit close; later next returns false and off-row getters reject access. End-of-results/close clear row state, and getRow returns zero off-row. Nine focused QuackResultSetFetchTest cases passed, covering server/protocol/transport failures, multiple pages, nulls, Calendar reads, and empty terminal pages. Independent review found no B22 issues. Broader connection ownership remains B17.

### B23. Column class metadata contradicts returned objects [P2]

Location: `sql/QuackResultSetMetaData.java:62-77`.

LIST/ARRAY claim List but return QuackArray; STRUCT claims Map but returns QuackStruct; MAP claims List but returns LinkedHashMap; UTINYINT claims Integer but returns Short. A consumer using getColumnClassName to interpret getObject can fail. Match actual supported wrappers/scalars. TIME_TZ also claims LocalTime while decoding a packed Long; a proper OffsetTime representation remains separate work. Confirmed live and compared with native JDBC.

**Partial follow-up (2026-09-05):** B3 corrected the numeric class metadata, including UTINYINT. The nested and TIME_TZ mismatches remain open under B23.

### B24. Column name normalization can return the wrong column [P2]

Location: `sql/QuackResultSet.java:55-56,114-115`.

For labels `stra\u00dfe` and `strasse`, getInt("strasse") returns the first value because uppercasing expands the former into the latter. Under Turkish locale, ID cannot be found as id. Use locale-independent matching without conflating distinct labels through multi-character case expansion. Locale.ROOT alone only solves the Turkish case. Confirmed against native JDBC.

### B25. BLOB-to-string conversions are lossy or return array identities [P2]

Location: `sql/QuackResultSet.java:140-144,312-315`.

getString on bytes FF 00 FE substitutes Unicode replacement characters. Native DuckDB returns the lossless escaped form `\xFF\x00\xFE`. getObject(..., String.class) on an ASCII BLOB instead returns `[B@...`. Define a lossless conversion and share it across supported string getters, or reject unsupported typed conversions. Confirmed live.

### B26. Long indices and stream lengths are narrowed unsafely [P2]

Location: `sql/QuackArray.java:52-56`; `sql/QuackBlob.java:30-60`; `sql/QuackPreparedStatement.java:220-257`.

Array and Blob slices at 4294967297 return the first element/byte. A Blob stream with negative length -4294967296 is accepted as zero. A character stream length of 4294967297 becomes one character; other values throw unchecked errors. The reader helper also consumes past the requested character count. Validate ranges and arithmetic as long before narrowing, and bound each read to the remaining requested count. Reproduced with small in-memory objects/streams.

### B27. Closed/freed objects retain payloads [P2]

Location: `sql/QuackResultSet.java:83-86`; `sql/QuackArray.java:21-23,85-88`; `sql/QuackStatement.java:170-175`.

ResultSet.close clears cursor buffers but retains currentChunk; Statement.close retains its result reference. Array.free sets a flag while retaining its list. A one-megabyte payload remains reachable through each tested closed/freed handle. Release owned references while preserving the validity of independently returned JDBC values where required. Confirmed by inspecting references in local probes; no unbounded application-wide heap leak is claimed without retained handles.

### B28. GEOMETRY APPEND omits the WKB discriminator [P2]

Location: `message/VectorCodec.java:719-739,315-318`.

The decoder optionally consumes field 99, but the encoder never emits it. Appending WKB POINT(1 2) makes DuckDB interpret it as legacy SPATIAL data and fail with HTTP 500. Adding field 99=WKB to the same local request succeeds and preserves bytes. Emit and validate the discriminator. Confirmed against released 1.5.5 as well as the available prerelease artifact.

### B29. Timestamp encoding mishandles numeric boundaries [P2]

Location: `message/VectorCodec.java:933-945`.

The multiplyExact of seconds can fail even when adding the positive fraction would produce a valid negative endpoint; the following unchecked addition can overflow in the opposite direction. Valid TIMESTAMP_NS 1677-09-21T00:12:43.145224194 throws; out-of-range 2262-04-11T23:47:16.999999999 wraps into 1677. Use a checked final conversion that handles negative-boundary cancellation and reserved sentinels. Confirmed locally.

**Resolution (2026-09-06): resolved.** All five timestamp APPEND variants use one checked seconds/fraction conversion. Negative instants shift one second into the fractional term to avoid premature underflow; final overflow and both infinity sentinels are rejected with QuackProtocolException. Existing floor-to-unit quantization is unchanged. DuckDB 1.5.5 defines infinities as +/-Long.MAX_VALUE, not Long.MIN_VALUE; validity-true signed-min finite values remain accepted. TIMESTAMP_S is bounded by Java's LocalDateTime range. Independent byte assertions cover every unit, finite endpoints, fractional truncation, sentinel collisions, and the reported examples. Live APPEND checks exact NS/US/TZ epochs, finiteness, non-nullness, and no writes on rejected overflow. B30 decoding policy remains separate. See V15.

### B30. Special temporal values turn into unrelated ordinary values [P2]

Location: `message/VectorCodec.java:535-538,581-609`.

DATE infinity becomes +5881580-07-11; TIMESTAMP_NS infinity becomes an ordinary-looking date in 2262; TIME 24:00 becomes 00:00. Define a deliberate representation or reject unsupported special values instead of silently substituting a finite value. Confirmed against 1.5.5.

**Resolution (2026-09-06): resolved by explicit rejection.** DATE and all five timestamp variants reject +/-infinity before constructing Java values. TIME/TIME_NS reject negative values, 24:00, and larger payloads rather than applying modulo; finite timestamps outside Java's range fail with QuackUnsupportedTypeException. DATE decoding/APPEND validates int32 days before narrowing. Temporal SEQUENCE checks each step with exact addition, preserving valid progressions whose index-product would overflow while rejecting actual overflow and later special values. B8's validity-first null handling, finite signed-min payloads, B11 Calendar semantics, and TIMETZ's packed Long representation remain unchanged. See V16.

### B31. BIGNUM binary storage is decoded as UTF-8 [P2]

Location: `message/VectorCodec.java:629-633`; related `sql/JdbcTypeMap.java:26`.

SELECT 123456789::BIGNUM returns binary-header garbage containing replacement characters. The physical VARCHAR fallback assumes text even for nontextual logical storage. Implement an explicit decoder, return a lossless documented representation, or throw QuackUnsupportedTypeException. Lack of feature support is acceptable; silent corruption is not. Audit other binary logical types using the same physical storage. Confirmed live.

**Resolution (2026-09-06): resolved by explicit unsupported-value errors.** Text decoding allowlists VARCHAR/CHAR (including JSON aliases); BLOB/BIT/GEOMETRY retain their raw bytes. Non-null BIGNUM, TYPE, and AGGREGATE_STATE now throw QuackUnsupportedTypeException, including compressed/nested values and malformed SEQUENCE encodings. APPEND rejects those logical values before the generic byte[]/toString escape paths or HTTP send. Null and empty vectors remain usable. BIGNUM advertises OTHER/Object instead of VARCHAR; explicit SQL casts to VARCHAR return exact decimal text.

The pinned DuckDB 1.5.5 physical-type audit confirms BIGNUM's header/magnitude bytes, TYPE's serialized LogicalType, and AGGREGATE_STATE's raw aggregate bytes are not UTF-8. Supported binary APPEND conversions and the separate GEOMETRY discriminator defect B28 are unchanged. TYPE/AGGREGATE_STATE are covered with synthetic codec/transport fixtures; live tests focus on BIGNUM. This does not claim newly implemented BIGNUM support. See V17.

### B32. Vector shapes and LEB128 terminal bits are incompletely checked [P2]

Location: `message/VectorCodec.java:335-410`; `codec/BinaryReader.java:115-126,150-165`.

A two-row VARCHAR vector with one encoded element decodes successfully as size one. Missing STRUCT children and invalid LIST offsets throw index errors. ARRAY multiplication is unchecked. Separately, nine 0x80 bytes followed by 0x02 overflow the tenth-byte shift and are accepted as zero by both LEB readers. Enforce cardinalities, child counts, slice bounds, checked arithmetic, and valid final-byte payload/sign-extension bits. Reproduced with independent local byte fixtures.

**Resolution (2026-09-05): resolved with B12.** Following B7's STRUCT child-count fix, expected counts and blob sizes are now checked before allocating vector/metadata collections. VARCHAR, STRUCT, LIST, chunk columns, ENUM labels, fixed-width payloads, selections, and validity masks enforce their cardinalities. Non-null LIST slices validate bounds, including overlapping slices without assuming disjoint/ordered storage; null-slot payloads remain uninterpreted. ARRAY and byte products use checked long arithmetic. ULEB/SLEB readers reject invalid tenth-byte payload or continuation bits while preserving signed/unsigned extrema and representable nonminimal encodings. Independent wire fixtures cover rejection and valid behavior. See V10.

### B33. getUDTs loses filtering and scalar alias base types [P2]

Location: `sql/QuackDatabaseMetaData.java:311-326`.

Filtering for Types.STRUCT returns ENUM and INTEGER aliases too. An INTEGER alias reports BASE_TYPE=OTHER rather than INTEGER. Native DuckDB 1.5.5.0 behaves correctly for both. Restore reference filtering and base-type mapping. Confirmed with live custom types.

### B34. STRUCT metadata emits invalid reserved-word identifiers [P2]

Location: `sql/JdbcTypeMap.java:107-113,129-134`.

A field named select produces STRUCT(select INTEGER), which cannot be used in a subsequent cast. Native JDBC quotes it. This is already acknowledged in CHANGELOG's known gaps but remains a real metadata/DDL round-trip defect. Quote reserved identifiers or otherwise produce valid DuckDB type syntax. Confirmed live.

### B35. getTime fails on valid TIMESTAMPTZ values [P2]

Location: `sql/QuackResultSet.java:228-234`.

OffsetDateTime has no conversion branch and falls into Time.valueOf with an ISO date-time string, yielding NumberFormatException. Native JDBC returns the time component for the tested timestamp. Handle the logical type explicitly and maintain a JDBC exception boundary. Confirmed live.

### B36. setMaxRows and unsupported isolation levels silently succeed [P2]

Location: `sql/SkeletalStatement.java:18-19`; `sql/SkeletalConnection.java:36-37`.

setMaxRows(1) still returns all three rows of a range query. This is a limit, unlike the fetch-size hint. setTransactionIsolation(SERIALIZABLE) succeeds while getTransactionIsolation remains REPEATABLE_READ. Enforce implemented options and reject unsupported/invalid requests. Audit requested result type, concurrency, and holdability for the same accept-but-ignore pattern. Confirmed live for max rows and isolation.

### B37. commit/rollback silently succeed in autocommit mode [P2]

Location: `sql/QuackConnection.java:76-89`; test `src/test/java/com/gizmodata/quack/jdbc/sql/QuackConnectionTransactionTest.java:98-109`.

JDBC requires SQLException when these methods are called in autocommit mode. The current implementation and a unit test endorse success. Reject this case while preserving a harmless no-op in manual mode with no pending transaction. Confirmed live. This is less severe than the actual append rollback failure.

### B38. Plain HTTP resolution changes the Host header [P2]

Location: `transport/QuackHttpTransport.java:141-173`.

All HTTP hostnames are rewritten to address literals to support fallback. A local request for http://localhost:port/quack sends Host=127.0.0.1:port. Host-routed HTTP gateways can reject or misroute it, and custom ProxySelectors see the rewritten URI rather than the logical endpoint. Host is reserved by URI validation, preventing an ordinary property workaround. Preserve logical authority while handling connection fallback, or narrowly scope fallback to situations where rewriting is acceptable. Confirmed with a local HTTP server; no deployed gateway was contacted.

### B39. Invalid TLS values silently select plaintext [P2]

Location: `transport/QuackUri.java:114,178-183`.

`tls=treu` produces an HTTP endpoint without error. Parse explicit true and false spellings and reject other nonblank values so a configuration typo cannot silently remove encryption. This is not a demand to change the default for existing local URLs. Confirmed locally.

**Resolution (2026-09-05): resolved.** Effective TLS values accept true/false, 1/0, yes/no, and on/off with locale-independent case handling and the existing trim behavior. Unknown nonblank values throw before resolving tokens or creating a transport; errors identify the option without echoing its value. Missing/blank values remain false. Canonical tls precedence over useEncryption and URL precedence for the same key are preserved. Four regression methods cover URL/Properties sources, both keys, valid spellings, rejected typos, defaults, precedence, and the JDBC exception boundary before transport creation. See V11.

### B40. URI diagnostics expose authentication material [P2]

Location: `transport/QuackUri.java:18-23,47-63,120-125`.

The record's generated toString includes the resolved token and all properties, including password/header credentials. URL-validation errors include the original URL, so an invalid URL with a token copies it into an exception message. Use redacted diagnostics, including nested URI parsing exceptions, and avoid logging raw properties. Verified using a synthetic placeholder only; no real secrets were inspected or disclosed.

**Resolution (2026-09-05): resolved.** QuackUri.toString redacts all string-bearing fields, including arbitrary property names and credentials misplaced in a host/database, while retaining primitive port/TLS state. Local URL/HTTP-URI, timeout, and header-validation errors use fixed input-free diagnostics and do not retain input-bearing parser causes. Token-source errors identify tokenEnv/tokenFile but omit configured names/paths and discard local I/O, invalid-path, and security causes. A narrow guard around JDK extra-header insertion prevents its raw-value diagnostics from escaping, including when callers bypass QuackUri parsing.

Operational token/property/header/URI accessors, token precedence/trimming, and valid header contents remain unchanged. This is not a global redactor: raw accessors, protocol request objects, arbitrary custom transport endpoint diagnostics, and server/query errors remain sensitive and are documented accordingly. No real credentials or token sources were used in tests; synthetic file fixtures and controlled child-JVM environments cover source resolution. See V12.

## Lower-Priority Issues And Tradeoffs

- **L1. Cursor position and closed-state checks.** `sql/QuackResultSet.java:74-78,94-95`: getRow returns the last row number after exhaustion instead of zero. Confirmed on a one-row result. Closed-state checks are also inconsistent across simple getters.
- **L2. Metadata statement ownership.** `sql/QuackDatabaseMetaData.java:30-33` and `sql/SkeletalStatement.java:48-49`: closing metadata results does not close their internally created statements, and closeOnCompletion is a no-op. Native JDBC closes the metadata statement. No independent server leak was established from this state difference alone.
- **L3. Abandoned server results.** `sql/QuackSession.java:266-274`: closing a partially read result deliberately leaves server result state until disconnect. This is a documented protocol limitation, not an accidental omission of an available release message. It remains an important operational concern for long-lived pooled connections; measure server memory during repeated abandoned large queries and evaluate cleanup options supported by the pinned protocol.
- **L4. Stale runtime identity.** `sql/QuackDriver.java:17-18`, `sql/QuackSession.java:76`, `sql/QuackDatabaseMetaData.java:340-345`: identity is stale: driver reports 0.1/0.1.0 while Maven is 0.7.0-SNAPSHOT, and database minor version is hardcoded zero. Derive build identity from one authoritative source and report actual server identity where applicable.
  **Resolution (2026-09-06): resolved.** Maven generates literal identity constants from project.version, preserving public compile-time constant APIs. Driver metadata and the handshake use the full generated identity. Session-cached server version supplies database major/minor and product version; absent identity retains the PRAGMA fallback, with unknown numeric components returning zero. DriverVersionTest passed 29 cases for Maven consistency, qualifiers, public constants, handshake identity, server versions, and fallback behavior. No runtime dependencies were added.
- **L5. Stale current-facing documentation.** `README.md:20,48,55`: tested-server and dependency examples lag the released/current versions. CLAUDE's roadmap still lists append and JDBC nested wrapping as future work despite implementation. Historical changelog sections need not be rewritten, but current-facing guidance should be accurate.
- **L6. Stale code descriptions and unused scaffolding.** `message/VectorCodec.java:30-44` and `message/MessageCodec.java:257-259`: stale implementation descriptions and an obsolete UNUSED collection add noise. Remove these after correctness work rather than spending review effort on cosmetic rewrites first.

## Native-Parity Questions

The native driver is a valuable oracle, not proof of JDBC specification compliance.

- **C1. Column type metadata parity.** getColumns reports OTHER for several array/temporal variants in both drivers. See `sql/QuackDatabaseMetaData.java:126-137`.
- **C2. Index metadata shape parity.** getIndexInfo omits INDEX_QUALIFIER and has nonstandard ordering in both drivers. See `sql/QuackDatabaseMetaData.java:180-185`.
- **C3. Function metadata shape parity.** getFunctions omits SPECIFIC_NAME and swaps standard schema/name ordering in both drivers. See `sql/QuackDatabaseMetaData.java:280-283`.
- **C4. Conversion and narrowing parity.** Some unchecked conversion errors and narrowing getters also occur in native 1.5.5.0. B3 focuses on the driver directing consumers into an undersized representation, not claiming all explicit narrowing is unique to Quack.

Before changing inherited metadata shapes, decide whether to preserve native compatibility or correct the JDBC contract, and test the decision in DBeaver. DBeaver UI behavior was not directly exercised during this review.

Explicitly unsupported FSST, native bind parameters, array ResultSets, updatable/scrollable cursors, and recursive nested JDBC wrapping were not counted as defects merely because they are missing. Returning incorrect data or pretending a requested control works is a different category.

## Build And Coverage Improvements

- **I1. Make oracle parity part of CI.** `.github/workflows/ci.yml:35-36` runs the default profile; `pom.xml:64,108-110` excludes oracle tests unless requested. The release gate therefore does not exercise the advertised parity suite.
  **Resolution (2026-09-06): resolved.** The test job now runs Maven with -Poracle, so tag publication's existing dependency on that job includes native oracle checks. Required integration execution and report auditing are separately tracked under I3.
- **I2. Gate snapshot publication on successful verification.** `.github/workflows/publish-snapshot.yml:3-6,25-29` is a separate push-triggered workflow using -DskipTests, independent of CI success. A failing main revision can still be deployed as a snapshot.
  **Resolution (2026-09-06): resolved.** Snapshot publication is now a job in CI with needs:test, so the oracle/required-integration/report checks must all succeed first. Publication checks out github.sha from that same run, not a moving main branch. Only main pushes or manual main CI runs can publish snapshots, and non-SNAPSHOT project versions are skipped. The independent publishing workflow is removed; stable publication remains tag-push-only. Static YAML/event-route and shell checks covered 28 event/dependency combinations, and the version-evaluation command returned the expected project version. Independent review found no I2 issues. Hosted execution awaits the next requested push.
- **I3. Require integration execution in CI.** Local auto-skipping is useful, but CI should fail if the fixture is unavailable and assert/log the CLI and loaded extension versions. Keep released-server checks separate from prerelease compatibility runs.
  **Resolution (2026-09-06): resolved with stock-server verification.** CI enables quack.it.required and audits every discovered integration-class report with no integration skips. Missing CLI/oracle and incorrect runtime, extension, or signature policies fail rather than silently skip. Fixtures install only signed core httpfs and quack into isolated directories and verify their actual loaded identities. CI builds no custom native extension and needs no private artifact. Fixture logs accompany failed reports. I2 subsequently gated snapshot publication on this verification job.
- **I4. Expand oracle checks beyond type codes/names.** `OracleParityIntegrationTest.java:82-86` compares only these two properties; add actual values, wrapper class compatibility, null handling, exact numeric boundaries, unsigned values, temporal zones, tuples, and typed conversions.
- **I5. Add JDBC state-transition and failure-path tests.** Cover close propagation, re-execution, commit holdability, partial batches, incomplete parameters, controls under blocked I/O, and FETCH failure after an initial page. Several current tests verify only that a method does not throw.
- **I6. Add independent wire fixtures and bounded malformed-input tests.** Encoder/decoder self-round-trips cannot reveal shared mistakes or differences from server null sentinels. Add dedicated logical-type codec tests, default-omitted fields, unknown metadata, vector shapes, LEB overflow, nesting limits, and geometry wire fixtures.
- **I7. Cover runtime/client diversity proportionately.** Test Java 17 and a current LTS, Linux and the primary desktop OSes, locale/timezone variants, and a small explicit DBeaver/DataGrip smoke-test checklist. Current CI is Ubuntu/JDK 17, while mise selects JDK 21.
- **I8. Harden release workflow permissions and dependencies.** Reduce release-job token permissions to what each job requires and consider immutable action revisions/artifact reuse. The workflow currently grants contents:write globally. These are hardening recommendations, not a finding that credentials have been compromised.
- **I9. Measure performance after data correctness is fixed.** Typed vectors are useful, but ResultSet primitive getters still route through rawValue/getObject and box values, contrary to the old no-boxing claim. Benchmark representative scans and allocation before changing that path; do not undertake a broad rewrite based on style preference.

## Strengths

- **S1. Layer separation.** Clear separation of protocol, types, transport, and JDBC layers.
- **S2. Runtime footprint.** Java 17 compilation target and no runtime dependencies.
- **S3. Transport testability.** Pluggable transport enables useful fault-injection tests without live services.
- **S4. Lazy fetching.** Avoids collecting every result page in client memory.
- **S5. Real-server integration.** Existing integration tests exercise real DuckDB, including append and nested values.
- **S6. Indirect token-source restrictions.** Token environment/file sources are restricted to connection Properties, with URL rejection tests.
- **S7. HTTPS authority preservation.** HTTPS retains the original hostname for certificate verification and SNI.
- **S8. Native oracle availability.** DuckDB's native JDBC driver is already available as an optional behavioral oracle.

These are good foundations. The highest-value work is tightening exact value semantics, transaction/resource ownership, and honest JDBC capability behavior, not reorganizing packages.

## Verification

### V1. Full-suite verification

Full command from the checkout during the original review:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle verify
```

Result: BUILD SUCCESS; 159 tests reported, zero failures, zero errors, one skip for IPv6 availability. All integration suites ran. Runtime: Java 21.0.2, Maven 3.9.16; project compilation targets Java 17. Native oracle: duckdb_jdbc 1.5.5.0.

### V2. Focused reproduction evidence

Reviewers also compiled source independently with --release 17 and ran focused probes. The codec review initially used the available 1.5.6 development artifact; the important live codec examples were subsequently rerun and confirmed against released 1.5.5. A successful test suite does not negate the independently reproduced gaps above.

Probe artifacts are under `/tmp/opencode/quack-statement-audit/`, `/tmp/opencode/quack-result-review/`, `/tmp/opencode/quack-codec-review-905/`, and `/tmp/opencode/QuackTransportReviewProbe.java`. They are outside the project and are not permanent regression tests.

### V3. Verification limits

The original review did not execute publication workflows or contact production servers, and no DBeaver UI compatibility certification is implied. V1-V2 describe that review. V5-V18 separately record implementation passes; they do not constitute verification of every remaining open finding. User-requested pushes trigger the repository's normal CI and snapshot workflows.

### V4. Workspace preservation

At the end of the original review, git status showed only the pre-existing modified CLAUDE.md and untracked DUCKDB_COMPATIBILITY.md. Those files remain untouched by the review and implementation. The earlier approved fixes, tests, and documentation were committed and pushed as af771d2 at the user's request, followed by B8 as 5f366ac. B11 was implemented subsequently and prepared as a separate user-requested commit, excluding those pre-existing changes.

### V5. Approved implementation verification

Approved order: B1, B5, B9, B10, B4. Implemented in `0.7.0-SNAPSHOT` with no new runtime dependencies. Targeted tests ran after each fix, followed by an independent diff review and regression tests for the additional Unicode preprocessing, floating-point representation, and catalog/schema ambiguity cases it identified.

Final full-build command:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result on 2026-09-05: BUILD SUCCESS; 182 tests reported, zero failures, zero errors, one IPv6 availability skip. This adds 23 test methods to the original oracle run, with multiple boundary cases inside several methods. All integration suites ran against released DuckDB 1.5.5 and the native oracle remained 1.5.5.0. Java runtime 21.0.2; Java 17 compilation target. `git diff --check` passed. Built artifact: `target/quack-jdbc-0.7.0-SNAPSHOT.jar`.

At the end of this first pass, only B1, B5, B9, B10, and B4 were resolved. B6 was still a known limitation for valid signed-128-bit APPEND values; the subsequent V6 pass resolves it separately along with B2 and B3. B1's explicit Unicode-whitespace template restriction remains intentional; separately bound Unicode values are preserved through the supported UTF-8 expression.

### V6. B6 and B2/B3 verification

Approved follow-up: B6, then B2+B3 together. Added 11 test methods and expanded existing signed-integer and wide-decimal matrices. B6's new tests reproduced the original failure before the helper was changed. Native metadata was checked against duckdb_jdbc 1.5.5.0 and its tagged Java source; oracle tests compare all ten integer logical types, metadata-driven getters, and fixed/variable arrays. An independent read-only review found no defects in the scoped changes.

Full builds on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle verify
```

| Profile | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Default clean verify | 189 | 0 | 0 | 1 |
| Oracle verify | 193 | 0 | 0 | 1 |

The sole skip is the existing IPv6 availability test; all integration suites ran. Runtime: Java 21.0.2, Java 17 compilation target, DuckDB CLI 1.5.5, native oracle 1.5.5.0. `git diff --check` passed. Built artifact remained `target/quack-jdbc-0.7.0-SNAPSHOT.jar`, with no new runtime dependencies. These fixes were later included in the user-requested commit af771d2. At the end of this pass, resolved IDs were B1-B6, B9, and B10, with B23's numeric subset addressed by B3.

### V7. B8 null-decoding verification

Approved follow-up: B8 only. Added six regression test methods and changed only the null branch of fixed-width object decoding in production code. Before the fix, the new wire-fixture and live scalar/nested tests failed on the reported TIME sentinel overflow. After the fix, all targeted tests passed, including the unchanged payload-length and non-null-value validation checks.

Full build on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result: BUILD SUCCESS; 199 tests reported, zero failures, zero errors, one existing IPv6 availability skip. All integration suites ran against released DuckDB 1.5.5 with native oracle 1.5.5.0. Java runtime 21.0.2; Java 17 compilation target. `git diff --check` passed. Built artifact: `target/quack-jdbc-0.7.0-SNAPSHOT.jar`. The pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md changes remain untouched and excluded from the B8 changes.

### V8. B11 Calendar conversion verification

Approved follow-up: B11 only. Added fourteen regression methods. The initial targeted run failed six tests on the original ignored-Calendar behavior before production changes. The completed targeted oracle run passed all 19 tests with `-Duser.timezone=America/Los_Angeles`; tests also switch between UTC, Los Angeles, and Apia internally and restore the original timezone. Timezone-changing test classes are isolated from concurrent JUnit execution. Independent review identified chronology and permissive-parser gaps; both were fixed and covered by additional regressions, with no remaining actionable B11 findings on re-review.

Full build on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result: BUILD SUCCESS; 213 tests reported, zero failures, zero errors, one existing IPv6 availability skip. All integration suites ran against released DuckDB 1.5.5 with native oracle 1.5.5.0. Java runtime 21.0.2; Java 17 compilation target. Built artifact: `target/quack-jdbc-0.7.0-SNAPSHOT.jar`. No new runtime dependencies or public APIs. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md changes remain untouched and excluded from the B11 changes.

### V9. B7 tuple preservation verification

Approved follow-up: continue in priority order, committing between fixes. B8 and B11 were already committed at the start of this pass. B7 adds seven regression methods. Initial fixtures and native parity reproduced the original attribute loss before production changes. Independent review found a missing-child validation gap in the new positional path; this was fixed and covered, and re-review found no remaining actionable B7 issues.

Full build on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result: BUILD SUCCESS; 220 tests reported, zero failures, zero errors, one existing IPv6 availability skip. All integration suites ran against released DuckDB 1.5.5 with native oracle 1.5.5.0. Java runtime 21.0.2; Java 17 compilation target. No new runtime dependencies. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md changes remain untouched and excluded from the B7 commit.

### V10. B12/B32 bounded decoding verification

Approved next priority after B7: B12 coordinated with B32. Independent review found no actionable issues in shared budgets, compressed recursion, shape checks, HTTP cleanup/replay behavior, or preserved APIs. Added 59 test invocations (including parameterized cases). The original maximum-list, byte-range-overflow, and deep-CONSTANT probes run in isolated processes with `-Xmx32m -Xss256k`, a watchdog, and no Error suppression; all fail with protocol exceptions instead of JVM resource errors.

Full builds on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -Poracle clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Both builds: BUILD SUCCESS; 279 tests, zero failures, zero errors, two environment skips (existing IPv6 bind availability and a new multi-address localhost fallback test). All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Actual runtimes: Java 17.0.2 and Java 21.0.2, both targeting Java 17. Live regressions include 1 MB strings/BLOBs, 20,000-element lists, lowering/raising per-connection limits, and 100,000 rows with a larger 24-chunk fetch batch. Existing null, tuple, unsigned, Calendar, and nested APPEND coverage remains green.

No runtime dependencies added. Limits, their conservative accounting, valid-message tuning, and separate deadline limitations are documented. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md remain untouched and excluded from this pass.

### V11. B39 TLS validation verification

Approved next fixes: B39 then B40, committed separately. The user-requested push of the preceding B7/B12/B32 commits completed first. B39's initial targeted run reproduced three failures before the parser change. Independent read-only review found no actionable B39 issues. A test assertion was corrected to compare the fixed diagnostic rather than treating the invalid token `tru` as distinguishable from the documented word `true` by substring search.

Full build on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result: BUILD SUCCESS; 283 tests, zero failures, zero errors, the same two networking-environment skips as V10. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0; Java 21.0.2 runtime and Java 17 compilation target. No live TLS endpoint was required: tests assert the selected HTTP scheme and rejection before any transport can be created. B40 diagnostics remain separate work.

### V12. B40 credential-safe diagnostic verification

B40 adds nine regression methods. Before their corresponding fixes, seven URI/header methods and two token-source methods reproduced unsafe diagnostics. Tests inspect full rendered stack traces, causes, and suppressed exceptions, not just outer messages. They also verify unchanged authentication accessors, valid URI/header values, source precedence, JDBC wrapping, and preservation of downstream transport details. Independent review found no actionable B40 issues within the documented local-configuration boundary. SecurityException catches were inspected, not exercised by installing a SecurityManager.

Full builds on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -Poracle clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Both builds: BUILD SUCCESS; 292 tests, zero failures, zero errors, the same two networking-environment skips as V10. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Actual runtimes: Java 17.0.2 and Java 21.0.2, both targeting Java 17. No runtime dependencies added. The pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md changes remain untouched and excluded from this pass.

### V13. B19 parameter-binding verification

The user-requested push of B39/B40 completed before this pass. B19 adds four unit methods and one integration method; all five reproduced the original defects before production changes. Coverage includes holes, explicit NULLs, binding reuse and clearing, zero parameters, setter/metadata bounds, no stream/array consumption on invalid indices, metadata probe isolation, batch snapshots, and no server writes or lazy BEGIN for rejected execution. Independent review found no production issues; a new test was corrected to use DML rather than SELECT for successful batch assertions, and re-review found no remaining scoped findings.

Full build on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Result: BUILD SUCCESS; 297 tests, zero failures, zero errors, the same two networking-environment skips as V10. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Runtime Java 21.0.2; Java 17 compilation target. B18 remains the next separate commit. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md changes remain untouched.

### V14. B18 batch failure verification

B18 follows the separate B19 commit. Two unit methods and one integration method reproduced the original count/closed-state defects before the fix. Fault injection tests every failure position in both implementations, exact successful counts (including zero), SQL state/vendor code/cause preservation, no later attempts, queue clearing, reuse, success, and empty/closed batches. A 12-case live matrix covers Statement/PreparedStatement, autocommit/manual transactions, and first/middle/last duplicate-key failures, checking persisted rows and rollback separately from reported execution counts. Independent read-only review found no actionable B18 findings and confirmed the JDBC stopped-prefix contract.

Full builds on 2026-09-05:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Both builds: BUILD SUCCESS; 300 tests, zero failures, zero errors, the same two networking-environment skips as V10. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Actual runtimes: Java 21.0.2 and Java 17.0.2, both targeting Java 17. No runtime dependencies added. The preceding B39/B40 push passed CI and snapshot publication; B19/B18 remain separate local commits until another push is requested. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md remain untouched.

### V15. B29 timestamp encoding verification

B19/B18 were pushed before this pass. B29 adds two independent codec tests and one live APPEND test; all three reproduced the original defects before the fix. Pinned DuckDB v1.5.5 timestamp and vector-storage sources establish the sentinel/validity distinction. Independent review found no B29 issues.

Full command on 2026-09-06: `QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify`.

Result: BUILD SUCCESS; 303 tests, zero failures/errors, two existing networking-environment skips. All integration suites ran against DuckDB 1.5.5 with native oracle 1.5.5.0. Runtime Java 21.0.2; Java 17 target. No new runtime dependencies. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md remain untouched.

### V16. B30 special temporal value verification

Five codec methods exercise independent FLAT/CONSTANT/DICTIONARY/SEQUENCE fixtures, all temporal endpoints, unsupported values, invalid masked payloads/alignment, DATE writes, and checked sequence progression. Two live methods cover scalar/nested special values and finite signed-min DATE/US/NS retrieval. Initial tests reproduced the corruption/rejection gaps. The signed-min live query uses explicit aliases: DuckDB 1.5.5's optimizer otherwise calls ToSQLString on folded timestamp constants and fails its own midnight conversion before Quack decoding. Aliases avoid that naming path without replacing or skipping raw temporal retrieval. Independent review found no B30 issues.

Full command on 2026-09-06: `QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify`.

Result: BUILD SUCCESS; 310 tests, zero failures/errors, two existing networking-environment skips. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Java 21.0.2 runtime; Java 17 target. B29's live APPEND boundary tests and all Calendar/null/nested regressions remain green. Pre-existing CLAUDE.md and DUCKDB_COMPATIBILITY.md remain untouched.

### V17. B31 and combined verification

B31 adds six regression methods. The initial focused run reproduced four unsafe read/encode paths before the fix; no deliberately malformed BIGNUM APPEND was sent to a real server. Independent review found no B31 issues. Tests cover independent binary fixtures, compression, null/empty metadata, APPEND rejection before HTTP, live scalar/nested BIGNUM errors, exact decimal-text casts, and supported text/raw-byte regressions.

Full builds on 2026-09-06:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -Poracle clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Both builds: BUILD SUCCESS; 316 tests, zero failures/errors, two existing networking-environment skips. All integration suites ran on DuckDB 1.5.5 with native oracle 1.5.5.0. Actual runtimes Java 17.0.2 and 21.0.2; Java 17 compilation target. B29/B30 are included in both builds. No runtime dependencies added; unrelated CLAUDE.md and DUCKDB_COMPATIBILITY.md remain untouched.

The user requested a published snapshot and Duckbridge's full suite through Quack before deciding on a stable release. Local success is not downstream certification. Outstanding P2 execution/lifecycle, typed-binding, conversion, and metadata findings and CI gates I1-I3 still require consideration; no stable tag is authorized by this verification record alone.

### V18. QK01/QK02 accounting correction and supplementary qualification

The exact supplementary source and original failure report were recovered from `/tmp/opencode/duckbridge-quack07/supplemental/SupplementalProbe.java` and `REPORT.md`. The shared Duckbridge report path contained the earlier 703-pass connector-suite report instead; it was not overwritten by this fix. The original three failing SQL projections are now permanent `DecodeBudgetIntegrationTest` cases, with no limit overrides, the default 12-chunk batch asserted, complete 65,537-row value/null checks, and actual FETCH counters. All three reproduced the budget error before the fix, then passed on both JDK 17 and 21 with a 128 MiB test heap.

`VectorValidationTest` adds three accounting matrices covering type costs, poisoned null slots, 0/63/64/65-row validity masks, exact-budget and one-byte-short boundaries, SEQUENCE conversion costs, compressed-source identity, and charging unused dictionary entries. Existing shared-budget, malformed-input, overflow, recursion, and bounded-subprocess checks remain green. Independent review found no actionable issues in the scoped correction.

Full verification commands:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -Poracle clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -Poracle clean verify
```

Both builds: BUILD SUCCESS; **322 tests, zero failures/errors, two existing networking-environment skips**. All integration suites ran on DuckDB 1.5.5 / native oracle 1.5.5.0. The focused three-case integration run also passed with `-DargLine=-Xmx128m` on each JDK, without changing decoder limits.

The **unchanged 36-case supplementary probe** was then executed against the locally built candidate JAR, not the published snapshot, using `/tmp/opencode/quack-budget-verify.sh`. It used the same pinned container image `sha256:d145f010fb4c52df33ef5fdb3068e276f6d96a6882ac63b3faf1dc7190972f47`, JDK 25 with `-Xmx512m`, signed DuckDB 1.5.5 / Quack `c154811`, two server threads, and default 12-chunk fetching. Result: **36 passed, 0 failed, 0 skipped; 11,403,812 assertions; 983,058 checked stream rows; 48 FETCH requests**. The isolated container was removed afterward. Logs and matrix are `qk-budget-candidate-20260906*` in the original supplementary directory; original reports/logs were preserved.

Tested candidate JAR SHA-256: `44e51cd37db67bb42fb34ed4f4149bf819c895b157b595de36a156b2adcdbbcf`. Unchanged probe source SHA-256: `f20ae3eb24b6d4d1b2d3a6943d8ee2360c93db7f631448e1398f293baa306b21`.

This does not requalify published build 10 or rerun the full connector suites. The separate T01-T08 connector wrong-row findings remain outside this correction. Actual HTTPS, live Doris FE/BE, soak testing, and remaining JDBC findings are not declared resolved. A replacement published artifact still needs identity-verified downstream revalidation before a stable-release decision.

### V19. Withdrawn server-feature experiment

The attempted B15 implementation expanded into a new, unofficial server capability. That was feature development, not a fix using functionality already present in supported servers. The explanation did not adequately disclose the trust and installation burden. The user rejected the approach, and it was removed before any push or publication. The experiment's test counts are not current release evidence. Independent B16/B22/L4/I1 fixes and stock-only I3 enforcement are retained; V20 supersedes this pass for verification.

### V20. Stock-server restoration

The custom server patch, native build tooling, capability headers, resultMetadata connection option, mandatory server-capability handshake, related response annotations, and custom-server tests were removed. Production transport/session behavior is back to stock v1. B16 re-execution cleanup, B22 FETCH error handling, L4 generated/runtime identity, and I1 oracle CI remain. I3 now uses only ordinary signed core httpfs/quack installations; eight fixture tests cover availability, executable resolution, required-oracle checks, and actual runtime/extension/signature/path validation.

Verification on Java 17.0.2 and Java 21.0.2, without any custom artifact or compatibility option:

```bash
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 mvn --batch-mode --no-transfer-progress -q -Poracle -Dquack.it.required=true clean verify
QUACK_IT_DUCKDB=/home/jayson/.local/share/mise/installs/duckdb/1.5.5/duckdb mvn --batch-mode --no-transfer-progress -q -Poracle -Dquack.it.required=true clean verify
```

Both builds passed **386 tests, zero failures/errors, and two existing networking-environment skips**. All integration suites ran against stock DuckDB 1.5.5 / signed core Quack c154811 with unsigned loading and unsafe crypto disabled. The CI report gate accepted **13/13 integration classes, 38 reports**. Independent rollback review found no remaining custom capability code or regression in the preserved fixes. No custom feature was pushed or published, and it is no longer a release prerequisite. B15 remains a documented protocol limitation; supporting a new server capability would require a separate, clearly described feature proposal.

## Top Five Priorities

This is the original approved implementation order, now completed as recorded under each ID and V5. It is retained for traceability, not presented as five outstanding tasks. The ranking prioritized security exposure and the risk of silently persisting incorrect data or violating rollback expectations, not ease of implementation. Original complexity estimates included a complete fix and targeted regression tests; they were not elapsed-time commitments. Low meant localized conversion/validation work, Medium coordinated paths and a boundary-test matrix, and High substantial semantic or API-design risk.

| Rank | ID | Work | Complexity | Why and implementation scope |
| --- | --- | --- | --- | --- |
| 1 | B1 | Safe prepared-parameter scanning | Medium-High | Parameter values can escape their intended boundary. Implement one lexical scanner covering DuckDB comments and quoting, shared by counting and substitution, with adversarial and ordinary SQL regression cases. No native-binding protocol change is needed. |
| 2 | B5 | Reject numeric APPEND overflow | Medium | Currently stores silently corrupted values. Check signed/unsigned ranges and DECIMAL precision across physical widths; test endpoints and server-backed rejection/read-back. Coordinate shared range logic with B6 if separately authorized. |
| 3 | B9 | Make connection-owned APPEND transactional | Medium-High | Rollback can leave supposedly uncommitted data persisted. Reconcile connection transaction ownership with the public low-level session API without breaking standalone sessions; test first append, commit, rollback, and subsequent appends. |
| 4 | B10 | Select and validate the URL catalog | Low-Medium | Unqualified writes can reach the wrong database. Initialize the server catalog before caching it, handle failed initialization cleanup, and test attached, nonexistent, and quoted catalog names. |
| 5 | B4 | Preserve exact numeric conversions | Low-Medium | Affects common BIGINT/DECIMAL reads and decimal writes. Remove floating-point and long intermediates for exact values; test large positive/negative integers, scales, and typed BigInteger retrieval. |

These ranks do not change any finding's ID. B41/V18 corrected the default-budget regression and shipped in build 11; the user reported passing artifact-verified downstream retesting. V20 verifies the retained B16/B22/L4/I1/I3 improvements against stock signed Quack after withdrawing the custom server feature. B15 is a protocol limitation, not a custom-plugin release prerequisite. Other findings remain tracked separately; no new feature should be presented as an existing capability or silently added to a defect-fix release scope.
