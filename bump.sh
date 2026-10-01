#!/usr/bin/env bash
# Bumps this project with the plugin itself, as published on GitHub Packages (profile "bump").
# Usage:  ./bump.sh -Dbump.part=patch [-Dbump.dryRun=true]
# Needs GITHUB_ACTOR and GITHUB_TOKEN (read:packages) in the environment. They are never printed.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_file="${script_dir}/.env"

# Only JAVA_HOME and MAVEN_HOME are taken from .env; its Maven settings (corporate mirror) are not used.
if [ -f "${env_file}" ]; then
	while IFS='=' read -r key value; do
		case "${key}" in
			JAVA_HOME | MAVEN_HOME) export "${key}=${value%$'\r'}" ;;
		esac
	done < <(grep -E '^(JAVA_HOME|MAVEN_HOME)=' "${env_file}" || true)
fi

: "${GITHUB_ACTOR:?set GITHUB_ACTOR to your GitHub user name}"
: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a GitHub token with the read:packages scope}"
if [ "$#" -eq 0 ]; then
	echo "Usage: $0 -Dbump.part=<major|minor|patch|build> [-Dbump.dryRun=true]" >&2
	exit 1
fi

mvn_cmd="mvn"
if [ -n "${MAVEN_HOME:-}" ]; then
	mvn_cmd="${MAVEN_HOME}/bin/mvn"
fi
if [ -n "${JAVA_HOME:-}" ]; then
	export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "Running: ${mvn_cmd} -s .mvn/bump-settings.xml -Pbump propagate:bump $*"
exec "${mvn_cmd}" -f "${script_dir}/pom.xml" -s "${script_dir}/.mvn/bump-settings.xml" -Pbump propagate:bump "$@"
