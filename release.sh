#!/usr/bin/env bash
# Releases this project (release version, commit, tag, atomic push, next SNAPSHOT). Run with --help for details.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${script_dir}"

plugin_ga="io.github.eliaschacon:version-propagator-maven-plugin"
version=""
next_version=""
dry_run=false
assume_yes=false

usage() {
	echo "Usage: $0 <version> [<next-version>] [--dry-run] [--yes]   (--help for details)" >&2
	exit 1
}

show_help() {
	cat <<EOF
Usage: $0 <version> [<next-version>] [--dry-run] [--yes]

Releases this project: sets <version> with the plugin built from this working tree (offline, profile "bump"),
commits 'Release <version>', tags v<version>, pushes the branch and the tag atomically (the tag starts
.github/workflows/release.yml), then sets the next SNAPSHOT, commits and pushes it.

Arguments:
  <version>       release version, e.g. 1.2.0 or v1.2.0 (not a SNAPSHOT)
  <next-version>  next development version (default: patch + 1 with -SNAPSHOT, e.g. 1.2.1-SNAPSHOT)
  --dry-run       run the checks and show the planned changes; nothing is changed, committed or pushed
  -y, --yes       do not ask for confirmation before pushing
  -h, --help      show this help

Before any change: on a branch, clean working tree, tag absent locally and on origin, branch not behind origin.
The five POMs and README.md change together; no GitHub token is needed. Tests run in the release workflow.
EOF
}

fail() {
	echo "Error: $*" >&2
	exit 1
}

for arg in "$@"; do
	case "${arg}" in
		--dry-run) dry_run=true ;;
		--yes | -y) assume_yes=true ;;
		--help | -h) show_help; exit 0 ;;
		-*) usage ;;
		*)
			if [ -z "${version}" ]; then version="${arg}"
			elif [ -z "${next_version}" ]; then next_version="${arg}"
			else usage
			fi
			;;
	esac
done
[ -n "${version}" ] || usage
version="${version#v}"
tag="v${version}"
case "${version}" in *SNAPSHOT*) fail "the release version must not be a SNAPSHOT: ${version}" ;; esac

# Maven: the project wrapper when .env exists (JDK, Maven and settings of this machine), plain mvn otherwise.
mvn() {
	if [ -f "${script_dir}/.env" ]; then
		bash "${script_dir}/mvn.sh" "$@"
	else
		command mvn "$@"
	fi
}

# --- Checks (nothing is changed before they all pass) ---------------------------------------------------------------
branch="$(git symbolic-ref --short -q HEAD)" || fail "not on a branch (detached HEAD)."
git diff --quiet && git diff --cached --quiet || fail "the working tree has uncommitted changes."
git rev-parse -q --verify "refs/tags/${tag}" >/dev/null && fail "tag ${tag} already exists locally."
if git ls-remote --exit-code --tags origin "refs/tags/${tag}" >/dev/null 2>&1; then
	fail "tag ${tag} already exists on origin."
fi
git fetch -q origin "${branch}" || fail "cannot fetch origin/${branch}."
[ "$(git rev-list --count "HEAD..origin/${branch}")" = "0" ] || fail "${branch} is behind origin/${branch}: pull first."

# --- Build the plugin used for the version changes -------------------------------------------------------------------
echo "Building the plugin (tests skipped; they run in the release workflow)..."
mvn -B -q install -DskipTests || fail "the build failed."
mvn -B -q -N help:evaluate -Dexpression=project.version -Doutput=target/release-version.txt >/dev/null \
	|| fail "cannot read the project version."
current="$(tr -d '[:space:]' < target/release-version.txt)"
plugin="${plugin_ga}:${current}:bump"

# Runs the bump goal of the plugin just built, with the README rules of the "bump" profile.
bump() {
	mvn -B -o -Pbump "${plugin}" "$@" || fail "the version change failed (nothing was committed)."
}

if [ -n "${next_version}" ]; then
	next_args=("-Dbump.newVersion=${next_version}")
else
	next_args=("-Dbump.part=patch" "-Dbump.snapshot=true")
fi

echo "Release ${current} -> ${version} (tag ${tag}) on ${branch}, then ${next_version:-the next patch SNAPSHOT}."
if [ "${dry_run}" = true ]; then
	[ "${current}" = "${version}" ] || bump "-Dbump.newVersion=${version}" -Dbump.dryRun=true
	echo "DRY RUN: nothing was changed, committed or pushed."
	exit 0
fi
if [ "${assume_yes}" != true ]; then
	read -r -p "Commit, tag ${tag} and push to origin? [y/N] " answer
	case "${answer}" in y | Y | yes | YES) ;; *) echo "Aborted: nothing was changed."; exit 1 ;; esac
fi

# --- Release ---------------------------------------------------------------------------------------------------------
if [ "${current}" != "${version}" ]; then
	bump "-Dbump.newVersion=${version}"
	git commit -q -am "Release ${version}"
fi
git tag -a "${tag}" -m "Release ${version}"
if ! git push --atomic origin "${branch}" "refs/tags/${tag}"; then
	echo "Error: the push failed. To undo locally: git tag -d ${tag}" >&2
	[ "${current}" != "${version}" ] && echo "                                     git reset --hard HEAD~1" >&2
	exit 1
fi
echo "Released ${version}: tag ${tag} pushed (release workflow started)."

# --- Next development version ----------------------------------------------------------------------------------------
bump "${next_args[@]}"
mvn -B -q -N help:evaluate -Dexpression=project.version -Doutput=target/release-version.txt >/dev/null \
	|| fail "cannot read the next version."
next="$(tr -d '[:space:]' < target/release-version.txt)"
git commit -q -am "Prepare next development version ${next}"
git push origin "${branch}" || fail "the push of ${next} failed: run 'git push origin ${branch}'."
echo "Next development version ${next} pushed."
