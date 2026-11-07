#!/usr/bin/env bash
# Fetch the released DuckDB 2.0.0 CLI used by the v3 oracle suite and verify
# its SHA256 against the binary recorded in dev-docs/RELEASE_0.13.1.md.
#
#   scripts/fetch-duckdb-2.sh [dest-dir]      default: /tmp/opencode/duckdb-2.0.0
#
# Prints the CLI path on success. Run the suite with:
#   QUACK_IT_DUCKDB=$(scripts/fetch-duckdb-2.sh) mvn -Poracle -Dduckdb.jdbc.version=2.0.0.0 verify
#   QUACK_IT_EXPECTED_VERSION=v2.0.0 QUACK_IT_EXPECTED_QUACK=f96420ed46 python3 scripts/check-integration-reports.py
set -euo pipefail

VERSION=2.0.0
EXPECTED_SHA256=c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb
URL="https://github.com/duckdb/duckdb/releases/download/v${VERSION}/duckdb-cli-linux-amd64.tar.gz"
DEST="${1:-/tmp/opencode/duckdb-${VERSION}}"
CLI="${DEST}/duckdb"

if [[ "$(uname -s)-$(uname -m)" != "Linux-x86_64" ]]; then
    echo "fetch-duckdb-2.sh: only the linux-amd64 archive is pinned; adjust URL and SHA256 for $(uname -s)-$(uname -m)" >&2
    exit 1
fi

if [[ ! -x "$CLI" ]]; then
    mkdir -p "$DEST"
    curl -fsSL "$URL" -o "${DEST}/duckdb-cli.tar.gz"
    tar -xzf "${DEST}/duckdb-cli.tar.gz" -C "$DEST"
    rm -f "${DEST}/duckdb-cli.tar.gz"
fi

ACTUAL_SHA256=$(sha256sum "$CLI" | cut -d' ' -f1)
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "fetch-duckdb-2.sh: SHA256 mismatch for $CLI" >&2
    echo "  expected $EXPECTED_SHA256" >&2
    echo "  actual   $ACTUAL_SHA256" >&2
    exit 1
fi

DUCKDB_AGENT_MODE=0 "$CLI" --version | grep -qE "^v${VERSION//./\\.}[[:space:]]" || {
    echo "fetch-duckdb-2.sh: $CLI did not report v${VERSION}" >&2
    exit 1
}

echo "$CLI"
