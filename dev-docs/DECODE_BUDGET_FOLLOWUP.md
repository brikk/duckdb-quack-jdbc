# Follow-up: compressed ranges can exceed the default decode budget

Triaged 2026-11-03 while preparing 0.12.1. **Not a release blocker.**
GitHub issues are disabled for `brikk/duckdb-quack-jdbc`, so this file records the
follow-up rather than changing repository settings or filing against another owner.

## Reproduction and result

Using signed DuckDB 2.0.0 (`d54cbbe3df`), core Quack `f96420ed46`, Java 17.0.2,
and this driver's normal default decode limits:

```sql
SET quack_target_batch_bytes=1048576;
SELECT i FROM range(40000000) t(i);
```

The first PREPARE response is refused with `Decoded allocation exceeds
maxDecodedBytes`, before JDBC exposes a row. The independently run probe is
`/tmp/opencode/quack-0121-evidence/DecodeBudgetProbe.java`; its result is in
`decode-budget-probe.log` in that directory. Changing the projection to
`i::INTEGER` passes on the same server/settings and reads first value 0.

The catalog evidence reports `VectorCodec.decodeStringLikeValue` in its stack;
this exact BIGINT reproduction instead fails at `VectorCodec.decodeVectorBody`'s
base allocation reservation. Thus the refusal is independently confirmed, but
the evidence does not establish that a BIGINT is incorrectly decoded as a string.
No catalog code or data was modified to investigate it.

## Assessment

This is a real **driver streaming/scalability limitation**, not malformed SQL,
transaction poisoning, or evidence that the protective budget check is wrong.
The server's batch target measures serialized bytes. SEQUENCE-compressed ranges
can represent millions of rows in very few bytes, so lowering that setting or
the PREPARE inline-row hint does not bound decoded rows. The driver eagerly
expands each vector into arrays. Its conservative base charge is `128 + 32 *
rowCount` per vector: 40 million rows alone require 1,280,000,128 budget bytes,
before other message accounting, versus the default 268,435,456. Even the actual
`long[]` would require about 320 MB, beyond that default.

Simply raising/removing limits, reducing the accounting constant, retrying the
PREPARE, or changing transaction close policy is not a safe fix. Byte-sized
batches and JDBC streaming do not currently guarantee bounded decoded expansion.
This limitation is already described under README's response/decoding limits
and lifecycle behavior; 0.12.1 does not change decoder behavior.

## Follow-up acceptance criteria

- Evaluate lazy SEQUENCE/CONSTANT/DICTIONARY vectors or a server-side decoded-row
  bound, including PREPARE inline results. Preserve exact JDBC types and values.
- Retain defensive allocation/nesting limits for adversarial compressed inputs;
  do not solve this by silently raising defaults or replaying SQL.
- Test very large compressed BIGINT and INTEGER ranges with fixed heap and
  multiple FETCHes where appropriate; also cover wide string and nested results.
- Reconcile the catalog's string-decoder stack with its exact submitted SQL and
  wire types before treating it as a distinct type-decoding defect.
