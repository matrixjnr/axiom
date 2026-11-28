#!/usr/bin/env bash
# Run a Gradle invocation again after a transient dependency-fetch failure.
# GitHub-hosted runners share Maven Central rate limits with other jobs; a 429 on the first
# attempt is common when several workflows start from the same push. A real build failure is
# still reported on the final attempt.
set -uo pipefail

for attempt in 1 2 3; do
  if ./gradlew "$@"; then
    exit 0
  fi
  if [ "$attempt" -lt 3 ]; then
    echo "Gradle attempt $attempt failed; retrying in $((attempt * 10))s" >&2
    sleep $((attempt * 10))
  fi
done
exit 1
