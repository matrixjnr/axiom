#!/usr/bin/env bash
# Runs the build check on each commit of a pull request, oldest first, so that every
# commit builds on its own (and git bisect works).
#
# Usage: check-each-commit.sh BASE_SHA HEAD_SHA
# Environment:
#   CHECK_COMMAND  command to run per commit (default: ./gradlew clean check --max-workers=2)
#   MAX_COMMITS    bound on the number of commits built (default: 20)
#   TIP_ONLY       when "true", build only the head commit (dependency update pull requests,
#                  whose first commit is made by the update bot and cannot carry checksums)
#
# Up to MAX_COMMITS non-merge commits are all built. A larger series is bounded: only commits
# that touch build files (build scripts, gradle/, build-logic/, gradle.properties, wrapper)
# are built, newest MAX_COMMITS of them, because those are the ones that can break a build
# while the final tree still passes. Commits that are not built are listed in a warning
# annotation, so a bounded run is never silent. A failing commit does not stop the run: every
# selected commit is built and all failures are reported.
set -euo pipefail

base="${1:?base sha}"
head="${2:?head sha}"
check_command="${CHECK_COMMAND:-./gradlew clean check --max-workers=2}"
max="${MAX_COMMITS:-20}"
tip_only="${TIP_ONLY:-false}"
build_paths=(
  ':(glob)**/*.gradle.kts' ':(glob)**/*.gradle' 'gradle' 'build-logic'
  'gradle.properties' 'settings.gradle.kts' 'gradlew' 'gradlew.bat'
)

mapfile -t commits < <(git rev-list --reverse --no-merges "$base..$head")
total="${#commits[@]}"
all_commits=("${commits[@]}")
if [ "$tip_only" = true ]; then
  echo "Tip-only mode: building only the head commit"
  commits=("$(git rev-parse "$head")")
elif [ "$total" -gt "$max" ]; then
  echo "$total commits exceed the bound of $max; building only commits that touch build files"
  selected=()
  for commit in "${commits[@]}"; do
    if [ -n "$(git diff-tree --no-commit-id --name-only -r "$commit" -- "${build_paths[@]}")" ]; then
      selected+=("$commit")
    fi
  done
  commits=("${selected[@]}")
  if [ "${#commits[@]}" -gt "$max" ]; then
    commits=("${commits[@]: -$max}")
  fi
fi

skipped=$((total - ${#commits[@]}))
if [ "$skipped" -gt 0 ]; then
  skipped_list=""
  for commit in "${all_commits[@]}"; do
    case " ${commits[*]} " in
      *" $commit "*) ;;
      *) skipped_list+="$(git log -1 --format='%h' "$commit") " ;;
    esac
  done
  echo "::warning::$skipped of $total commits were not built on their own: $skipped_list"
fi

echo "Building ${#commits[@]} of $total commits"
failed=0
for commit in "${commits[@]}"; do
  echo "::group::$(git log -1 --format='%h %s' "$commit")"
  git checkout --quiet --detach "$commit"
  if ! bash -c "$check_command"; then
    echo "::error::Commit $(git log -1 --format='%h %s' "$commit") does not build on its own"
    failed=1
  fi
  echo "::endgroup::"
done
exit "$failed"
