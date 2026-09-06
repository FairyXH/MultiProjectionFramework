#!/bin/sh
# Pinned upstream release assets, verified against GitHub's SHA-256 asset digests.
# Review https://github.com/JingMatrix/LSPatch/releases/latest before updating these pins.
set -eu

variant=release
if [ "${1:-}" = "--debug" ]; then
    variant=debug
    shift
fi
if [ "${1:-}" = "--help" ]; then
    printf 'Usage: %s [--debug] [output-directory]\n' "$0"
    exit 0
fi
if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [--debug] [output-directory]\n' "$0" >&2
    exit 2
fi

release=v1.2
build=487
case "$variant" in
    release) sha256=d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165 ;;
    debug) sha256=5f2afaecc0fe36296bdd607acce2521a1dadd1c7c8ab6e3d163cbc77d8121153 ;;
esac
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_dir=${1:-"$repo_dir/artifacts/lspatch"}
mkdir -p -- "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd)
asset="lspatch-$release-$build-$variant.jar"
destination="$output_dir/$asset"

verify() {
    actual=$(shasum -a 256 "$1")
    actual=${actual%% *}
    if [ "$actual" != "$sha256" ]; then
        printf 'SHA-256 mismatch for %s\nExpected: %s\nActual:   %s\n' "$1" "$sha256" "$actual" >&2
        return 1
    fi
}

if [ -f "$destination" ]; then
    # Recheck the cache on every invocation; never execute or silently replace a bad JAR.
    verify "$destination"
else
    download=$(mktemp "$output_dir/.lspatch-download.XXXXXX")
    trap 'rm -f -- "$download"' EXIT
    trap 'exit 1' HUP INT TERM
    printf 'Downloading LSPatch %s build %s (%s)...\n' "$release" "$build" "$variant" >&2
    curl --fail --location --silent --show-error --retry 3 \
        --connect-timeout 20 --max-time 300 --proto '=https' --proto-redir '=https' \
        --output "$download" \
        "https://github.com/JingMatrix/LSPatch/releases/download/$release/$asset"
    verify "$download"
    mv -f -- "$download" "$destination"
fi

# Keep stdout to one absolute path, so callers can use LSPATCH_JAR="$(./tools/download-lspatch.sh)".
printf '%s\n' "$destination"
