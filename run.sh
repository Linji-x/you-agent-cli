#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
maven_version=3.9.11
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
  unzip -q "$archive" -d "$tools_dir"
  rm -f "$archive"
fi

cd "$project_root"
if [ "${1:-}" = "--verify" ] && [ "$#" -eq 1 ]; then
  "$maven_command" -q clean test package
  java -jar target/you-agent-cli.jar --demo
  exec java -jar target/you-agent-cli.jar --benchmark
fi
"$maven_command" -q -DskipTests package
exec java -jar target/you-agent-cli.jar "$@"
