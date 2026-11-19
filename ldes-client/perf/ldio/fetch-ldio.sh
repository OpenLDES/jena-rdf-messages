#!/bin/sh
# Extracts LDIO from the official image openldes/ldi-orchestrator:3.2.2 (linux/amd64) without Docker: the application
# and the three connectors measure-ldio.mjs needs, from their image layers on Docker Hub, each checked against its
# digest. With Docker, `docker create` and `docker cp <container>:/ldio` give the same files.
#   ldes-client/perf/ldio/fetch-ldio.sh [target directory, default ldes-client/target/ldio]
set -eu
target=${1:-$(dirname "$0")/../../target/ldio}
repo=openldes/ldi-orchestrator
# The amd64 manifest of 3.2.2 (built 6 November 2026) and its layers with /ldio/ldio-application.jar,
# lib/ldio-instrumentation.jar, lib/ldio-ldes-client-jar-with-dependencies.jar and lib/ldio-noop-out-jar-with-dependencies.jar
layers="
1086762bc37ada013daf653c8976bb6fc5e9c246123fb44c61ac38145071e12c
2dd8cb1f17287f8cd8f7490d96dad007c79c791660626b86b14ab97c032611e1
caae5d701428d30fba2ddd5c3b21dd792dc5a271be0c8acf3526b6a529505e9f
8ceaeced2b3fa2d87dfffa463a721182cbc365e573c2b01392911aa4951608c6
"
token=$(curl -fsS "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repo:pull" |
    sed 's/.*"token":"\([^"]*\)".*/\1/')
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/root"
for digest in $layers; do
    curl -fsSL -H "Authorization: Bearer $token" "https://registry-1.docker.io/v2/$repo/blobs/sha256:$digest" -o "$work/layer.tgz"
    echo "$digest  $work/layer.tgz" | sha256sum -c --quiet
    tar -xzf "$work/layer.tgz" -C "$work/root"
done
mkdir -p "$target"
cp -r "$work/root/ldio/." "$target/"
ls -l "$target" "$target/lib"
