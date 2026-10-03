# Documentation site

The documentation site is built from the Markdown in this repository: `README.md` is the
landing page, every `docs/NAME.md` is a page, and the API reference is the aggregated Javadoc of
the published modules. There are no copies: edit the Markdown and the site follows.

## Preview locally

```sh
./gradlew docsSite
```

writes the site to `build/site` and fails on a broken link or anchor. Open
`build/site/index.html` in a browser; the pages use only relative links, so they also work from
a `file:` URL. To see them under a project path like the one GitHub Pages uses:

```sh
mkdir -p build/preview && ln -sfn "$PWD/build/site" build/preview/axiom
python3 -m http.server --directory build/preview 8000
```

and open `http://localhost:8000/axiom/`. For a faster loop while editing text, run
`./gradlew docsPages checkDocsLinks`, which skips the Javadoc (`build/docs-pages`).

## How it is built

| Task | What it does |
| --- | --- |
| `docsPages` | Converts `README.md` and `docs/*.md` to HTML with the dependency-free converter in `build-logic` and adds `site.css`. Fails when a document links to a page or repository file that does not exist. |
| `aggregateJavadoc` | One Javadoc for the main sources of every published module, without the `internal` packages (`build/docs-api`). |
| `docsSite` | Assembles `build/site` (pages in the root, Javadoc under `api/`) and runs `checkSiteLinks` on it. |
| `checkDocsLinks` | Checks the pages for broken file links, broken `#anchor`s, duplicate ids and site-absolute paths. It is part of `./gradlew check`, so a broken link in a document fails the normal build. |
| `checkSiteLinks` | The same check on the assembled site, which also verifies links into `api/`. |

`README.md` becomes `index.html` and `docs/NAME.md` becomes `NAME.html`, all in one directory
next to `site.css`. Every link between pages is therefore a plain relative file name that works
under `/axiom/`, at the root of a custom domain and from disk. Site-absolute links (starting with
`/`) are rejected for that reason.

Links in the Markdown are written as for GitHub and rewritten by the converter:

- `other.md`, `../README.md` and `other.md#section` point at pages and stay on the site;
- a link to any other file or directory in the repository (`LICENSE`, `../examples/rest-api`)
  becomes a link to it on GitHub, and the build fails if it does not exist;
- `#section` anchors use GitHub's heading ids, so the same link works on both.

The converter supports the Markdown the documents use: headings, paragraphs, bullet and numbered
lists (nested), fenced code blocks, block quotes, tables, rules, inline code, emphasis, links and
images. Raw HTML is escaped, not passed through. Reference-style links, setext headings,
indented code blocks and strikethrough are not supported; use the plain forms.

## Add a page

1. Create `docs/NAME.md` with a single `# Title` heading; the title is the menu label.
2. Link to it from related pages with `[text](NAME.md)`.
3. To place it in the menu, add `"Group/NAME.md"` to `siteNavigation` in
   `build-logic/src/main/kotlin/axiom.docs-site.gradle.kts`. A page that is not listed is still
   published, under "More", so it cannot go missing by accident. Listing a page that does not
   exist yet is allowed and the entry is skipped until the file appears.
4. Run `./gradlew docsSite`.

## Deployment

`.github/workflows/docs.yml` is separate from the Build workflow; the required `check` job does
not depend on it. A pull request that touches `docs/**`, `README.md`, Java sources, the build
logic or the workflow builds the site, runs the link check and keeps the result as the
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

The site contains only relative links, so no content changes when the domain is added; the old
`https://matrixjnr.github.io/axiom/` address redirects to the custom domain.
