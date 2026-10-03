#!/bin/sh
# Builds the documentation site into build/site (docs/site.md). Run from the repository root after
# `./gradlew jekyllSource aggregateJavadoc`. Needs Ruby and Bundler; the gems come from
# site/Gemfile.lock only (CI installs with the lock frozen).
set -eu

export BUNDLE_GEMFILE="${PWD}/site/Gemfile"
rm -rf build/site
bundle exec jekyll build --strict_front_matter \
  --source build/jekyll --destination build/site \
  --config build/jekyll/_config.yml,build/jekyll/_config.generated.yml "$@"
# The Javadoc is copied after the build, so Jekyll never scans it.
cp -R build/docs-api build/site/api
