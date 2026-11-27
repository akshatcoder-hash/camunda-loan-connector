#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if command -v mvn >/dev/null 2>&1 && [[ -n "${JAVA_HOME:-}" ]]; then
  (cd "$ROOT/connector" && mvn clean package)
else
  mkdir -p "$ROOT/.m2"
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e MAVEN_CONFIG=/tmp/maven/.m2 \
    -v "$ROOT/.m2:/tmp/maven/.m2" \
    -v "$ROOT:/workspace" \
    -w /workspace/connector \
    maven:3.9.11-eclipse-temurin-21 \
    mvn clean package
fi

mkdir -p "$ROOT/element-templates"
cp "$ROOT/connector/element-templates/legacy-core-banking-connector.json" \
  "$ROOT/element-templates/legacy-core-banking-connector.json"

echo "Built connector/target/legacy-core-banking-connector-0.1.0-SNAPSHOT.jar"
