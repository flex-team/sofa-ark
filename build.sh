#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
MVN="${MVN:-mvn}"
ARGS=(-B -Dmaven.javadoc.skip=true -Dgpg.skip -Djacoco.skip=true)
if [[ -n "${MAVEN_REPO:-}" ]]; then ARGS+=(-Dmaven.repo.local="$MAVEN_REPO"); fi
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then ARGS+=(-s "$MAVEN_SETTINGS"); fi
if [[ -n "${SKIP_TESTS:-}" ]]; then ARGS+=(-DskipTests); fi
cd "$ROOT" && "$MVN" "${ARGS[@]}" "$@" install
