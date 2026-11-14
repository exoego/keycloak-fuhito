#!/usr/bin/env bash
# Runs the aislop quality gate on the tracked files of this repository.
#
# aislop is a third-party tool from npm, so it never runs on the host.
# It runs in a container with no network, no privileges, and a read-only copy of the files.
# Only the step that downloads the tool has the network, and that step sees no project file.
#
# Usage: scripts/aislop.sh [aislop arguments]
# With no arguments it runs "ci . --human", the same gate as CI.
# A file that git does not track yet is not scanned. Run "git add" on it first.
set -euo pipefail

# renovate: datasource=npm depName=aislop
AISLOP_VERSION=0.20.0
# renovate: datasource=docker depName=node
NODE_VERSION=24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
NODE_IMAGE="node:$NODE_VERSION"

root=$(git rev-parse --show-toplevel)
tool="$root/build/aislop/$AISLOP_VERSION"
copy=$(mktemp -d)
trap 'rm -rf "$copy"' EXIT

run=(docker run --rm --cap-drop ALL --security-opt no-new-privileges --user "$(id -u):$(id -g)"
    --read-only --memory 2g --pids-limit 256
    --tmpfs /tmp -e HOME=/tmp -e npm_config_update_notifier=false)

if [ ! -x "$tool/node_modules/.bin/aislop" ]; then
    mkdir -p "$tool"
    "${run[@]}" -v "$tool:/tool" -w /tool "$NODE_IMAGE" \
        npm install --ignore-scripts --no-audit --no-fund --loglevel=error "aislop@$AISLOP_VERSION"
fi

# The files as they are now, with changes that are not committed yet.
snapshot=$(git -C "$root" stash create)
git -C "$root" archive "${snapshot:-HEAD}" | tar -x -C "$copy"

if [ "$#" -eq 0 ]; then
    set -- ci . --human
fi
"${run[@]}" --network none \
    -e AISLOP_NO_TELEMETRY=1 -e DO_NOT_TRACK=1 -e AISLOP_NO_HISTORY=1 -e CI=true \
    -v "$tool:/tool:ro" -v "$copy:/src:ro" -w /src "$NODE_IMAGE" \
    /tool/node_modules/.bin/aislop "$@"
