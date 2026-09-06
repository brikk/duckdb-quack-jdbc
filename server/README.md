# B15 Server Result Metadata

`duckdb-quack-result-metadata.patch` is the server half of the B15 JDBC fix.
It adds HTTP result metadata version **1**, not a new Quack binary protocol.
This is a patch delivery against pinned source, **not** a claim that an official
Quack release already includes the fix or that a stable public patched server
release exists.

| Input | Repository | Exact commit |
| --- | --- | --- |
| DuckDB v1.5.5 | https://github.com/duckdb/duckdb | `d8cdaa33fda8df955cc76ef58a280f68f4cd43fa` |
| Quack patch base | https://github.com/duckdb/duckdb-quack | `c1548111c1bfd16207e22fd3cb7e4bde1335b9d0` |

The approved client contract is `resultMetadata=required` by default. That mode
requires this patch (or a server implementing exactly this capability), requests
version 1 on each relevant HTTP request, and fails closed when required response
metadata is absent, duplicated, unsupported or invalid. There is no automatic
fallback to alias-based classification. Stock, unpatched servers are supported
only with the explicit JDBC property `resultMetadata=legacy`. Legacy mode omits
negotiation and retains B15's limitation: a user-controlled alias such as
`SELECT 42::BIGINT AS Count` can be mistaken for an update count, and no-result
statements cannot be classified reliably. This directory supplies the server
patch/build contract; it does not implement the JDBC client changes.

## HTTP Capability Version 1

Negotiation is per `POST /quack`, not session state. HTTP field names are
case-insensitive. Send exactly one `X-Quack-Result-Metadata: 1` field. The parsed
field value must be exactly `1` (normal HTTP surrounding whitespace handling is
performed by httplib). Omitting the field requests legacy behavior, even on a
connection that previously requested metadata. A successful Connection response
does not opt subsequent requests in automatically.

An unsupported or malformed value (including empty, `0`, `2`, `01`, `1, 1`, or
`1, 2`) or multiple occurrences, including duplicate `1` values or differently
cased field names, yields **HTTP 400** with `Content-Type: text/plain` and exactly:

```text
Invalid X-Quack-Result-Metadata header
```

The body includes a final newline. Validation happens before the request body is
read/deserialized or `HandleMessage` is called: no authentication/authorization
SQL, application SQL, or new connection is executed/created for a rejected
header. This is an HTTP error, not a binary Quack ErrorResponse.

For a valid opt-in, response metadata depends on the actual response message:

| Response | Additional HTTP headers |
| --- | --- |
| Successful ConnectionResponse | `X-Quack-Result-Metadata: 1` |
| Successful PrepareResponse | `X-Quack-Result-Metadata: 1` and exactly one `X-Quack-Result-Kind` |
| ErrorResponse, FetchResponse, SuccessResponse (including append/disconnect) | Neither metadata nor kind header |

Without opt-in, neither header is emitted. Normal Quack response bodies retain
`Content-Type: application/vnd.duckdb`; SQL/authentication/authorization errors
remain the existing v1 ErrorResponse, not a successful metadata response.
Unsupported negotiation is rejected on every POST, including FETCH/APPEND and
disconnect, even though their successful responses carry no metadata.

The kind values below are frozen, case-sensitive strings for HTTP metadata
version 1. They are not DuckDB enum names or enum ordinals on the wire.

| `X-Quack-Result-Kind` | DuckDB `StatementReturnType` | Meaning/examples |
| --- | --- | --- |
| `query` | `QUERY_RESULT` | A query ResultSet, including empty SELECT, aliases `Count`/`Success`, and DML with RETURNING, even when empty |
| `changed_rows` | `CHANGED_ROWS` | An affected-row count, including zero-row INSERT/UPDATE/DELETE without RETURNING |
| `nothing` | `NOTHING` | No JDBC ResultSet; e.g. ordinary DDL, BEGIN/COMMIT/ROLLBACK, SET |

The authoritative source is `QueryResult::properties.return_type` from the
**actually executed SQL**, after any authorization-function SQL rewrite. Neither
the submitted SQL text, a column name/type, nor the presence of a batch determines
the kind. `QuackServer::HandleMessageInternal` captures the property before
`CreateBatch` can drain and destroy/reset the result, then stores it in a
server-local `PrepareResponseMessage` member for the HTTP handler. The patch also
allows a successful `NOTHING` result with no column names instead of rejecting
it as "Query did not return any columns"; that execution correction is not gated
on the HTTP header. Missing/unknown internal return types are server errors,
never guessed kinds. FETCH batches require no repeated kind header.

The v1 binary message schema, generated Serialize/Deserialize code, field IDs,
message IDs and connection protocol version remain unchanged. The server-local
member is not serialized. No binary version bump or private binary field/message
ID allocation is involved. Opted-in and legacy Prepare bodies have the same
encoding (separate executions naturally have different result UUIDs).

Existing CORS behavior is preserved: `Access-Control-Allow-Origin: *` on POST;
OPTIONS returns 204 with methods `GET, POST, OPTIONS` and allowed headers `*`.
Only successful opted-in responses add `Access-Control-Expose-Headers`:
`X-Quack-Result-Metadata` for Connection, and
`X-Quack-Result-Metadata, X-Quack-Result-Kind` for Prepare. This is not a CORS
hardening change. Proxies must preserve both request and response metadata;
stripping it is incompatible with required mode.

## Reproduction

Prerequisites: Bash, Git, CMake (3.19+ recommended; required for archive reuse),
Ninja, Python 3, `realpath`, `sha256sum`, and a Linux C/C++ compiler/linker toolchain.
Network access to the two GitHub repositories is required. Choose a new or empty
OUT directory with an existing parent, outside this JDBC checkout. Existing
nonempty output directories are rejected rather than reset/reconfigured.

From the JDBC repository root, the self-contained build is:

```bash
env -u DUCKDB_SOURCE_DIR -u QUACK_PREBUILT_DUCKDB_ARCHIVE \
  CMAKE_BUILD_PARALLEL_LEVEL=4 \
  tools/build-quack-test-extension.sh /tmp/opencode/quack-b15-ci
```

The script fetches the exact DuckDB and Quack commits into `OUT/duckdb` and
`OUT/quack`, verifies DuckDB's v1.5.5 tag against the pin, runs `git apply --check`
before applying the tracked patch, and generates `OUT/quack-only.cmake`. That
config includes DuckDB's `third_party/httplib` and loads only Quack with
`DONT_LINK`. It bypasses Quack's default config/submodules and does not fetch
httpfs or use a parity-extension checkout/cache.

The normal static graph builds DuckDB's PIC `duckdb_static` core and embedded
third-party objects, the dummy static-extension loader, and the loadable Quack
extension. `EXTENSION_STATIC_BUILD=ON` preserves the standard hidden archive
symbols, section GC and extension metadata footer. Shell, unit tests,
core_functions, parquet, jemalloc, and automatic compiler launchers are disabled
for this minimal build. Static here means the DuckDB core is linked into a
loadable extension, not that the artifact has no system-library dependencies.

The build uses Release/Ninja, defaults to four build jobs, and accepts
`CMAKE_BUILD_PARALLEL_LEVEL` to change the job count. Its output contract is:

```text
OUT/build/extension/quack/quack.duckdb_extension
```

It prints that path and the artifact's SHA-256. The pins and tracked patch make
the source/build recipe reproducible; byte-identical artifacts across different
toolchains, paths or sysroots are not promised. Keep all runtime artifacts out of
Git. No installation, signing, global extension-cache writes, or server startup
is performed by the script.

### Optional Local Reuse

Neither opt-in is a CI requirement. Both are read-only inputs, and all
configuration/build output still goes into a fresh OUT:

```bash
DUCKDB_SOURCE_DIR=/absolute/clean/duckdb-v1.5.5 \
QUACK_PREBUILT_DUCKDB_ARCHIVE=/absolute/qualified/libduckdb_static.a \
CMAKE_BUILD_PARALLEL_LEVEL=2 \
tools/build-quack-test-extension.sh /tmp/opencode/quack-b15-reuse
```

`DUCKDB_SOURCE_DIR` alone avoids the DuckDB download, not the core compilation.
Its HEAD and v1.5.5 tag must both match the exact pin; tracked/untracked changes
and `extension/extension_config_local.cmake` (even if ignored) are rejected. It
is used as the CMake source directory, never fetched, patched, or reconfigured
in place. Quack is always fetched into OUT and patched from this repository.

`QUACK_PREBUILT_DUCKDB_ARCHIVE` avoids rebuilding the core. For verifiable local
reuse, this script accepts **only** the previously qualified Linux x86-64 PIC
v1.5.5 archive with SHA-256:

```text
d822d48dd74622055d99e69232c989a80f3472d1f99e27ef01e98e8529f1d99e
```

No filename/version-string inference or unchecked arbitrary archive is accepted.
The archive already embeds its third-party core objects, including mbedtls and
jemalloc; disabling jemalloc in the new configuration does not remove the
allocator already in that archive. A deferred CMake hook replaces only the
`duckdb_static` link dependency; the ordinary dummy loader and extension link
recipe are retained. This opt-in can also be used with a freshly fetched DuckDB
source. For any other archive, omit the opt-in and use the normal full build.

## Runtime And Qualification

Qualification to date is local **Linux x86-64 only**, using DuckDB CLI v1.5.5,
GCC 15.2.0, CMake 4.3.3, Ninja and Python 3.12 with the qualified archive-reuse
route. The full cold-core build is the default CI recipe, not a locally completed
qualification. No manylinux, macOS, Windows, other DuckDB version, or production
portability/signing claim is made. Build with a deployment-compatible
compiler/sysroot and requalify on each intended platform.

The local patched artifact loaded in the official v1.5.5 CLI and passed
`ldd -r` without unresolved symbols. Raw HTTP probes covered per-request
negotiation, all three result kinds, SQL authorization rewrites/errors, legacy
v1 bodies, 27 invalid-header probes without SQL side effects/new connections,
streaming FETCH, CORS, disconnect and unchanged native-client multi-batch
decoding. These are prior local qualification results, not a public release or
tests run automatically by this build script.

Quack's existing session-ID RNG calls `DatabaseInstance::GetEncryptionUtil(false)`.
Creating a session requires the crypto provider supplied by a matching **httpfs**
runtime extension. A bare CLI can LOAD Quack but its first connection fails with
HTTP 500 without that provider:

```text
DuckDB currently has a read-only crypto module loaded. Please ensure httpfs is loaded using LOAD httpfs
```

Provision a trusted v1.5.5 httpfs artifact for the same platform separately, then
explicitly LOAD it before creating Quack sessions. It is a runtime dependency,
not an httpfs source/build dependency of this minimal recipe. Do not enable
`force_mbedtls_unsafe`; the qualified runs kept it false.

For an isolated **test-only** CLI session, use a fresh HOME/extension directory,
disable autoinstall/autoload, and load explicit paths, for example:

```bash
mkdir /tmp/opencode/quack-b15-runtime
env HOME=/tmp/opencode/quack-b15-runtime \
  XDG_CONFIG_HOME=/tmp/opencode/quack-b15-runtime \
  /absolute/duckdb-v1.5.5 -unsigned -init /dev/null :memory:
```

```sql
SET extension_directory='/tmp/opencode/quack-b15-runtime';
SET autoinstall_known_extensions=false;
SET autoload_known_extensions=false;
LOAD '/absolute/trusted/v1.5.5/httpfs.duckdb_extension';
LOAD '/tmp/opencode/quack-b15-ci/build/extension/quack/quack.duckdb_extension';
SELECT version(), current_setting('force_mbedtls_unsafe');
CALL quack_serve('quack:127.0.0.1:9999', token='local-test-only-token');
```

Keep the CLI alive while testing and exit it afterward. `-unsigned` permits this
locally built test artifact; it is **not a production recommendation**. Production
delivery needs trusted signing/distribution and deployment validation, rather
than globally disabling signature checks or relying on this local artifact.

## Upstream Delivery

The patch contains exactly three source changes: `src/include/quack_message.hpp`,
`src/quack_http_server.cpp`, and `src/quack_server.cpp` (48 insertions, 5 deletions).
To deliver/review upstream, start from Quack's exact base commit and apply:

```bash
git -C /absolute/clean/duckdb-quack apply --check \
  /absolute/fork-quack-jdbc/server/duckdb-quack-result-metadata.patch
git -C /absolute/clean/duckdb-quack apply \
  /absolute/fork-quack-jdbc/server/duckdb-quack-result-metadata.patch
```

Deliver this capability contract and the HTTP/native-v1 regression cases with
the patch. Agree on the HTTP names/version with upstream before claiming public
support, rebase/requalify deliberately when advancing either pin, and do not
allocate private binary IDs as an alternative. Until an upstream release is
verified to implement this contract, deploy a separately built patched server
for default-required JDBC clients, or explicitly accept the B15 limitation via
`resultMetadata=legacy` for stock servers.
