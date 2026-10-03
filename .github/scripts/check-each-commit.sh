#!/usr/bin/env bash
# Runs the build check on each commit of a pull request, oldest first, so that every
# commit builds on its own (and git bisect works).
#
# Usage: check-each-commit.sh BASE_SHA HEAD_SHA
# Environment:
#   CHECK_COMMAND  command to run per commit (default: ./gradlew clean check --max-workers=2)
#   MAX_COMMITS    bound on the number of commits built (default: 20)
#
# Up to MAX_COMMITS non-merge commits are all built. A larger series is bounded: only commits
# that touch build files (build scripts, gradle/, build-logic/, gradle.properties, wrapper)
# are built, newest MAX_COMMITS of them, because those are the ones that can break a build
# while the final tree still passes.
set -euo pipefail

base="${1:?base sha}"
head="${2:?head sha}"
check_command="${CHECK_COMMAND:-./gradlew clean check --max-workers=2}"
max="${MAX_COMMITS:-20}"
build_paths=(
  ':(glob)**/*.gradle.kts' ':(glob)**/*.gradle' 'gradle' 'build-logic'
  'gradle.properties' 'settings.gradle.kts' 'gradlew' 'gradlew.bat'
)

mapfile -t commits < <(git rev-list --reverse --no-merges "$base..$head")
total="${#commits[@]}"
if [ "$total" -gt "$max" ]; then
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

echo "Building ${#commits[@]} of $total commits"
failed=0
for commit in "${commits[@]}"; do
  echo "::group::$(git log -1 --format='%h %s' "$commit")"
  git checkout --quiet --detach "$commit"
  if ! bash -c "$check_command"; then
    echo "::error::Commit $(git log -1 --format='%h %s' "$commit") does not build on its own"
    failed=1
    echo "::endgroup::"
    break
  fi
  echo "::endgroup::"
done
exit "$failed"
