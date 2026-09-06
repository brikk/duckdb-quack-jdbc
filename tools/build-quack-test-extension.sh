#!/usr/bin/env bash
set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[[ $# == 1 ]] || die "usage: $0 OUT (new or empty directory; see server/README.md)"
readonly duckdb_commit=d8cdaa33fda8df955cc76ef58a280f68f4cd43fa
readonly quack_commit=c1548111c1bfd16207e22fd3cb7e4bde1335b9d0
readonly archive_sha256=d822d48dd74622055d99e69232c989a80f3472d1f99e27ef01e98e8529f1d99e
repo=$(realpath -- "$(dirname -- "${BASH_SOURCE[0]}")/..")
patch="$repo/server/duckdb-quack-result-metadata.patch"
jobs=${CMAKE_BUILD_PARALLEL_LEVEL:-4}
[[ $jobs =~ ^[1-9][0-9]*$ ]] || die "CMAKE_BUILD_PARALLEL_LEVEL must be a positive integer"
for tool in git cmake ninja python3 sha256sum; do
    command -v "$tool" >/dev/null || die "missing build prerequisite: $tool"
done
[[ -f $patch ]] || die "missing tracked server patch: $patch"

# Reuse is explicitly opt-in and never configures an existing build directory.
duckdb_source=${DUCKDB_SOURCE_DIR:-}
if [[ -n $duckdb_source ]]; then
    duckdb_source=$(realpath -e -- "$duckdb_source")
    [[ $(git -C "$duckdb_source" rev-parse HEAD) == "$duckdb_commit" ]] || die "DUCKDB_SOURCE_DIR is not pinned v1.5.5"
    [[ $(git -C "$duckdb_source" rev-parse 'refs/tags/v1.5.5^{commit}') == "$duckdb_commit" ]] || die "DUCKDB_SOURCE_DIR needs the matching v1.5.5 tag"
    [[ -z $(git --no-optional-locks -C "$duckdb_source" status --porcelain --untracked-files=all) ]] || die "DUCKDB_SOURCE_DIR must be clean"
    [[ ! -e $duckdb_source/extension/extension_config_local.cmake ]] || die "DUCKDB_SOURCE_DIR has a local extension config (possibly git-ignored)"
fi
archive=${QUACK_PREBUILT_DUCKDB_ARCHIVE:-}
if [[ -n $archive ]]; then
    archive=$(realpath -e -- "$archive")
    [[ -f $archive ]] || die "QUACK_PREBUILT_DUCKDB_ARCHIVE must be a regular file"
    actual_sha256=$(sha256sum -- "$archive")
    [[ ${actual_sha256%% *} == "$archive_sha256" ]] || die "archive is not the SHA-256-qualified PIC v1.5.5 core; omit QUACK_PREBUILT_DUCKDB_ARCHIVE for a full build"
fi

[[ ! -L $1 ]] || die "OUT must not be a symlink"
[[ -d $(dirname -- "$1") ]] || die "OUT parent directory must already exist"
mkdir -p -- "$1"
out=$(realpath -e -- "$1")
shopt -s nullglob dotglob
entries=("$out"/*)
[[ ${#entries[@]} == 0 ]] || die "OUT must be new or empty; refusing to reconfigure $out"
# CMake list arguments cannot represent semicolons in these paths.
[[ $out$duckdb_source$archive != *';'* ]] || die "build paths must not contain semicolons"

fetch_source() {
    local url=$1 commit=$2 destination=$3
    git init --quiet "$destination"
    git -C "$destination" remote add origin "$url"
    git -C "$destination" fetch --depth 1 --no-tags origin "$commit"
    git -C "$destination" checkout --quiet --detach FETCH_HEAD
    [[ $(git -C "$destination" rev-parse HEAD) == "$commit" ]] || die "unexpected source revision in $destination"
}

if [[ -z $duckdb_source ]]; then
    duckdb_source="$out/duckdb"
    fetch_source https://github.com/duckdb/duckdb.git "$duckdb_commit" "$duckdb_source"
    git -C "$duckdb_source" fetch --depth 1 origin refs/tags/v1.5.5:refs/tags/v1.5.5
    [[ $(git -C "$duckdb_source" rev-parse 'refs/tags/v1.5.5^{commit}') == "$duckdb_commit" ]] || die "upstream v1.5.5 tag does not match the pin"
fi
fetch_source https://github.com/duckdb/duckdb-quack.git "$quack_commit" "$out/quack"
git -C "$out/quack" apply --check "$patch"
git -C "$out/quack" apply "$patch"

# This generated config deliberately bypasses Quack's submodules/default config.
cat > "$out/quack-only.cmake" <<'CMAKE'
include_directories("${CMAKE_SOURCE_DIR}/third_party/httplib")
duckdb_extension_load(quack SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/quack" DONT_LINK)

if(QUACK_PREBUILT_DUCKDB_ARCHIVE)
    cmake_minimum_required(VERSION 3.19)
    if(NOT EXTENSION_STATIC_BUILD OR NOT EXISTS "${QUACK_PREBUILT_DUCKDB_ARCHIVE}")
        message(FATAL_ERROR "Core reuse requires static extension linking and a verified archive")
    endif()
    add_library(quack_prebuilt_duckdb STATIC IMPORTED GLOBAL)
    set_target_properties(quack_prebuilt_duckdb PROPERTIES
        IMPORTED_LOCATION "${QUACK_PREBUILT_DUCKDB_ARCHIVE}"
        INTERFACE_LINK_LIBRARIES "${CMAKE_DL_LIBS};Threads::Threads")

    # Retain the normal dummy loader, hidden symbols, GC and extension footer.
    function(quack_reuse_static_core)
        get_target_property(libraries quack_loadable_extension LINK_LIBRARIES)
        if(NOT "duckdb_static" IN_LIST libraries)
            message(FATAL_ERROR "Expected the normal duckdb_static link dependency")
        endif()
        list(TRANSFORM libraries REPLACE "^duckdb_static$" "quack_prebuilt_duckdb")
        set_property(TARGET quack_loadable_extension PROPERTY LINK_LIBRARIES "${libraries}")
    endfunction()
    cmake_language(DEFER CALL quack_reuse_static_core)
endif()
CMAKE

cmake -S "$duckdb_source" -B "$out/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DEXTENSION_STATIC_BUILD=ON \
    -DBUILD_SHELL=OFF \
    -DBUILD_UNITTESTS=OFF \
    -DDUCKDB_EXTENSION_CONFIGS="$out/quack-only.cmake" \
    -DQUACK_PREBUILT_DUCKDB_ARCHIVE="$archive" \
    '-DSKIP_EXTENSIONS=core_functions;parquet' \
    -DENABLE_JEMALLOC=OFF \
    -DCMAKE_C_COMPILER_LAUNCHER= \
    -DCMAKE_CXX_COMPILER_LAUNCHER=
cmake --build "$out/build" --target quack_loadable_extension --parallel "$jobs"

artifact="$out/build/extension/quack/quack.duckdb_extension"
[[ -s $artifact ]] || die "build did not produce $artifact"
printf '\nUnsigned test extension: %s\n' "$artifact"
sha256sum -- "$artifact"
