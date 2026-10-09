#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
maven_version=3.9.11
maven_sha512=03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076
tools_dir="$project_root/.tools"
maven_home="$tools_dir/apache-maven-$maven_version"
maven_command="$maven_home/bin/mvn"

if [ ! -x "$maven_command" ]; then
  mkdir -p "$tools_dir"
  archive="$tools_dir/apache-maven-$maven_version-bin.zip"
  url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$maven_version/apache-maven-$maven_version-bin.zip"
  echo "Bootstrapping Maven $maven_version..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$archive"
  else
    wget -q "$url" -O "$archive"
  fi
  if command -v sha512sum >/dev/null 2>&1; then
    actual_sha512=$(sha512sum "$archive" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual_sha512=$(shasum -a 512 "$archive" | awk '{print $1}')
  else
    rm -f "$archive"
    echo "A SHA-512 utility (sha512sum or shasum) is required." >&2
    exit 1
  fi
  if [ "$actual_sha512" != "$maven_sha512" ]; then
    rm -f "$archive"
    echo "Maven archive SHA-512 mismatch." >&2
    exit 1
  fi
  unzip -q "$archive" -d "$tools_dir"
  rm -f "$archive"
fi

cd "$project_root"
if [ "${1:-}" = "--verify" ] && [ "$#" -eq 1 ]; then
  "$maven_command" -q clean verify
  java -jar target/you-agent-cli.jar --demo
  exec java -jar target/you-agent-cli.jar --benchmark
fi
"$maven_command" -q -DskipTests package
exec java -jar target/you-agent-cli.jar "$@"
