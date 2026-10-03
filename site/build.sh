#!/bin/sh
# Builds the documentation site into build/site (docs/site.md). Run from the repository root after
# `./gradlew jekyllSource aggregateJavadoc`. Needs Ruby and Bundler; the gems come from
# site/Gemfile.lock only (CI installs with the lock frozen).
set -eu

export BUNDLE_GEMFILE="${PWD}/site/Gemfile"
rm -rf build/site

# The address the site is served at. By default it is the one in site/_config.yml (the project address
# https://matrixjnr.github.io/axiom). When the repository serves the site elsewhere (a custom domain,
# where the base path is empty) the Docs workflow passes the address GitHub Pages reports:
#   AXIOM_SITE_URL      the origin, e.g. https://axiom.jsgalactic.com  (no trailing slash)
#   AXIOM_SITE_BASEURL  the base path, e.g. /axiom, or empty for a site at the root
config=build/jekyll/_config.yml,build/jekyll/_config.generated.yml
if [ -n "${AXIOM_SITE_URL:-}" ]; then
  {
    echo "# Written by site/build.sh from AXIOM_SITE_URL and AXIOM_SITE_BASEURL."
    echo "url: \"${AXIOM_SITE_URL}\""
    echo "baseurl: \"${AXIOM_SITE_BASEURL:-}\""
  } > build/jekyll/_config.address.yml
  config="${config},build/jekyll/_config.address.yml"
fi

bundle exec jekyll build --strict_front_matter \
  --source build/jekyll --destination build/site \
  --config "${config}" "$@"
# The Javadoc is copied after the build, so Jekyll never scans it.
cp -R build/docs-api build/site/api
