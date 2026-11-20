# 0.14.1: certified against DuckDB 2.0.1

Release date: 2026-11-20. Coordinates: `dev.brikk.duckdb:quack-jdbc:0.14.1`.
Verification-only release: no production class changes from 0.14.0. The
immutable `v0.14.1` tag identifies the release commit.

## Publication confirmation

- Release commit: `163f332675644c18c004dda34742a301644a8486`, head of `release/0.14.1`.
- [Tag / GitHub Release v0.14.1](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.14.1)
  is a normal release with binary/sources/Javadoc jars and `SHA256SUMS`.
- [Release CI run 44761213951](https://github.com/brikk/duckdb-quack-jdbc/actions/runs/44761213951)
  succeeded on the first attempt: the 1.5.6 and 2.0.1 jobs each passed 787
  tests with zero failures/errors and one optional preview skip; the report
  checker confirmed 17/17 required integration classes with the 2.0.1 pins
  (`runtime=v2.0.1`, Quack `3bc63c01f8`).
- Central deployment `db61c382-0fc4-4058-a1e9-ddc6ece3c3ba` published;
  `maven-metadata.xml` reports `0.14.1` as `release`.
- **Central binary JAR SHA256:** `011fbe4b36ce1fe47694ce609ef9aad63a18d958c36a41ea0d77e52f1d301474`.

## Scope

DuckDB 2.0.1 (`8d78114e90`) pins `duckdb-quack` at `3bc63c01`. The delta over
2.0.0's `f96420ed46` does not touch the wire; see
[DUCKDB_COMPATIBILITY.md](DUCKDB_COMPATIBILITY.md) for the review and the full
Java 17/21 x 1.5.6/2.0.1 matrix plus the 2.0.0 regression run.
