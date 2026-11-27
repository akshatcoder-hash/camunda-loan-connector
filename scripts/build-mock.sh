#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if command -v mvn >/dev/null 2>&1 && [[ -n "${JAVA_HOME:-}" ]]; then
  (cd "$ROOT/mock-legacy" && mvn clean package -DskipTests)
else
  mkdir -p "$ROOT/.m2"
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e MAVEN_CONFIG=/tmp/maven/.m2 \
    -v "$ROOT/.m2:/tmp/maven/.m2" \
    -v "$ROOT/mock-legacy:/workspace" \
    -w /workspace \
    maven:3.9.11-eclipse-temurin-21 \
    mvn clean package -DskipTests
fi

echo "Built mock-legacy/target/mock-legacy-core-banking-0.1.0-SNAPSHOT.jar"
