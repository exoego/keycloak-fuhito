#!/usr/bin/env bash
# Runs the aislop quality gate on the tracked files of this repository.
#
# aislop is a third-party tool from npm, so it never runs on the host.
# It runs in a container with no network, no privileges, and a read-only copy of the files.
# Only the build of the image has the network, and that build sees no project file.
#
# Usage: scripts/aislop.sh [aislop arguments]
# With no arguments it runs "ci . --human", the same gate as CI.
# A file that git does not track yet is not scanned. Run "git add" on it first.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
context="$root/scripts/aislop"
# A change of the Dockerfile gives a new tag, so an old image is never used by mistake.
image="keycloak-fuhito-aislop:$(git hash-object "$context/Dockerfile" | cut -c1-12)"
copy=$(mktemp -d)
trap 'rm -rf "$copy"' EXIT

if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build --quiet --tag "$image" "$context" >/dev/null
fi

# The files as they are now, with changes that are not committed yet.
snapshot=$(git -C "$root" stash create)
git -C "$root" archive "${snapshot:-HEAD}" | tar -x -C "$copy"

if [ "$#" -eq 0 ]; then
    set -- ci . --human
fi
docker run --rm --network none --cap-drop ALL --security-opt no-new-privileges \
    --user "$(id -u):$(id -g)" --read-only --memory 2g --pids-limit 256 --tmpfs /tmp \
    -e HOME=/tmp -e AISLOP_NO_TELEMETRY=1 -e DO_NOT_TRACK=1 -e AISLOP_NO_HISTORY=1 -e CI=true \
    -v "$copy:/src:ro" -w /src "$image" "$@"
