---
title: Documentation site
parent: Operations
nav_order: 6
---

# Documentation site

The documentation site is built from the Markdown in this repository with
[Jekyll](https://jekyllrb.com/) and the [just-the-docs](https://just-the-docs.com/) theme:
`README.md` is the home page, every `docs/NAME.md` is a page, and the API reference is the
aggregated Javadoc of the published modules. There are no copies: edit the Markdown and the site
follows. The theme gives the site a navigation tree, search (the index is built at build time and
served from the site), light and dark colour schemes that follow the reader's setting, callouts,
copy buttons on code blocks and a layout for phones. All of the theme's scripts and styles are
served from the site itself; the only third-party requests a page makes are the status badges at
the top of the home page, which are images.

## Preview locally

Jekyll needs Ruby 3.3 and Bundler (no Docker). Once:

```sh
cd site
bundle config set --local path vendor/bundle
bundle install
cd ..
```

Then, from the repository root:

```sh
./gradlew jekyllSource aggregateJavadoc    # build/jekyll (the Jekyll source) and build/docs-api
site/build.sh                              # build/site, with the Javadoc in build/site/api
./gradlew checkSiteLinks                   # the same link check CI runs on the built site
```

`site/build.sh` is also what CI runs. To serve it with live reload while editing text (rerun
`./gradlew jekyllSource` after each change to a document; the Javadoc is not part of the served
site) and open `http://localhost:4000/axiom/`:

```sh
BUNDLE_GEMFILE=site/Gemfile bundle exec jekyll serve --source build/jekyll \
  --config build/jekyll/_config.yml,build/jekyll/_config.generated.yml
```

## How it is built

| Part | What it does |
| --- | --- |
| `site/` | The Jekyll configuration (`_config.yml`), the three menu group pages, the footer and head includes, `Gemfile` and `Gemfile.lock`, `build.sh`. |
| `jekyllSource` | Assembles `build/jekyll` from `site/`, `docs/*.md` and `README.md` (as `index.md`, with the home page's front matter). Rewrites links between documents for the flat page layout, links to other repository files (`../examples/rest-api`) to GitHub, and fails on a link, anchor or front matter problem. Writes `_config.generated.yml` with the version, the commit when `GITHUB_SHA` is set, and the logo, dark logo and favicon when `branding/` has them (the directory is copied to the site as `branding/`, which the README's logo needs). |
| `aggregateJavadoc` | One Javadoc for the main sources of every published module, without the `internal` packages (`build/docs-api`). |
| `site/build.sh` | Runs `jekyll build` on `build/jekyll` into `build/site` and copies the Javadoc to `build/site/api` afterwards, so Jekyll never scans it. The menu entry "API reference" links to it. |
| `checkDocsLinks` | Checks the Markdown sources for broken file links, broken `#anchor`s, site-absolute paths and missing front matter. It needs no Ruby and is part of `./gradlew check`, so a broken link in a document fails the normal build. |
| `checkSiteLinks` | Checks the built site (all pages, the theme assets, and that every link into `api/` resolves to a file), including each `#anchor`. CI runs it after Jekyll; it is not part of `check` because it needs the Ruby build. |

Links in the Markdown are written as for GitHub and stay valid there:

- `other.md`, `../README.md` and `other.md#section` point at pages and stay on the site;
- a link to any other file or directory in the repository (`LICENSE`, `../examples/rest-api`)
  becomes a link to it on GitHub, and the build fails if it does not exist;
- `#section` anchors use GitHub's heading ids (`kramdown.input: GFM`), so the same link works on
  both, and the build fails when one does not exist.

All pages sit in one flat directory under the site's base path (`/axiom`). A site-absolute link
(starting with `/`) in a document is rejected, because it would break on GitHub or under the base
path.

## Add a page

1. Create `docs/NAME.md` with a single `# Title` heading.
2. Add the front matter, which GitHub shows as a small table; keep it to these three fields:

   ```text
   ---
   title: Menu label
   parent: Guides
   nav_order: 3
   ---
   ```

   `parent` is one of the group pages in `site/`: `Getting started`, `Guides` or `Operations`.
   `nav_order` orders the page inside its group. `checkDocsLinks` fails when one is missing, so a
   page cannot disappear from the menu.
3. Link to it from related pages with `[text](NAME.md)`.
4. Run `./gradlew checkDocsLinks`, or build the site as above.

To add a group, add a page to `site/` with `has_children: true` and a `nav_order`. The README has
no front matter of its own; the home page's is added by `jekyllSource`.

Callouts are available through the theme (`{: .warning }` after a block quote, defined in
`site/_config.yml`). The documents do not use them because that line would show on GitHub as text.

## Supply chain

The site is built with `ruby/setup-ruby` and Bundler, not with `actions/jekyll-build-pages`, and
the theme is the `just-the-docs` gem rather than a `remote_theme`:

- `site/Gemfile.lock` pins every gem to an exact version and records the SHA-256 of each gem file
  from RubyGems (`CHECKSUMS`). `ruby/setup-ruby` installs with the lock frozen, so a gem whose
  content differs from the recorded hash fails the build. The theme is `just-the-docs` 0.12.0 at
  an exact hash, and Ruby is `3.3.6`.
- `jekyll-build-pages` runs the fixed set of gems of the `github-pages` bundle, accepts only the
  themes that bundle allows, and loads any other theme with `jekyll-remote-theme`, which downloads
  it from GitHub while the site builds. Pinning that download to a commit leaves the content of
  the plugin and of the rest of the bundle outside the repository's lock file.
- Nothing is fetched while Jekyll runs: no remote theme, no CDN. The search index is generated
  from the pages, and the theme's JavaScript (including `lunr`) ships in the gem and is copied
  into the site. Mermaid and analytics, which the theme can load from third parties, are not
  enabled.
- Every action in the workflow is pinned to a full commit SHA with a version comment.

To update the theme or Jekyll, change the version in `site/Gemfile`, run
`bundle lock --update --add-checksums` in `site/`, rebuild locally, and review the diff of
`Gemfile.lock` in the pull request.

## Deployment

`.github/workflows/docs.yml` is separate from the Build workflow; the required `check` job does
not depend on it. A pull request that touches `docs/**`, `README.md`, `site/**`, Java sources, the
build logic or the workflow builds the site, runs both link checks and keeps the result as the
`github-pages` artifact for seven days; nothing is deployed. A push to `main` builds the same
site and deploys it with `actions/configure-pages` and `actions/deploy-pages` in a second job
that alone has `pages: write` and `id-token: write`. Deployments are serialized in the `pages`
concurrency group and use the `github-pages` environment. The workflow can also be started by
hand on `main` (Actions, Docs, Run workflow), which is how to publish the first time after
enabling Pages.

### Enabling GitHub Pages (repository owner)

In the repository, open Settings, Pages, and set Source to "GitHub Actions". Until then the
deploy job fails at `configure-pages`; pull request builds are not affected. After the first
successful deploy the site is at `https://matrixjnr.github.io/axiom/`.

The README links to the site. If the address changes (for example a custom domain), update the link
and badge at the top of `README.md`.

### Custom domain (repository owner, optional)

To serve the site at `axiom.jsgalactic.com`:

1. In the DNS zone of `jsgalactic.com`, add a `CNAME` record for the host `axiom` with the value
   `matrixjnr.github.io` (a CNAME is correct for a subdomain; no `A` records are needed). Do not
   add a wildcard record.
2. In the repository, Settings, Pages, Custom domain: enter `axiom.jsgalactic.com` and save.
   GitHub checks the DNS record, which can take a few minutes.
3. Once the check passes, enable "Enforce HTTPS". GitHub provisions the certificate itself.
4. Recommended: verify the domain in the account or organization settings (Pages, Add a domain),
   which adds a `TXT` record and prevents another account from taking over the name if the
   record is ever left dangling.
5. Optional: add a `docs/CNAME` file containing only `axiom.jsgalactic.com`. The build copies it
   to the site root. With the Actions deployment the setting in step 2 is what counts, so the
   file only documents the choice in the repository.

6. Serve the site from the root of the domain: in `site/_config.yml` set `url` to
   `https://axiom.jsgalactic.com` and `baseurl` to `""`, and in
   `build-logic/src/main/kotlin/axiom.docs-site.gradle.kts` set `siteUrl` and `baseUrl` of
   `checkSiteLinks` to match (`https://axiom.jsgalactic.com` and `""`). The Markdown does not
   change, because it contains no site addresses. The old `https://matrixjnr.github.io/axiom/`
   address redirects to the custom domain.
