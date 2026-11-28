# Axiom branding

| File | Use |
| --- | --- |
| `logo.png` | The mark as a PNG for README and social previews. |
| `logo.svg` | Mark and custom vector wordmark for light backgrounds (documentation site). |
| `logo-dark.svg` | Mark and wordmark for dark backgrounds. |
| `logo-mark.svg` | The mark alone as SVG: avatars, favicon source, slide corners. |
| `favicon.svg` | Browser tab icon; the mark reads down to 16 px. |
| `wordmark.svg`, `wordmark.png` | The wordmark alone, without the mark. |

The mark is three stacked chevrons on a navy tile: the teal upper layer is the live request path,
the pale layers below are the stable foundations. Flat colors and broad strokes keep it legible at
small sizes. The wordmark is custom vector lettering; the PNG is a raster export for documents
that do not render SVG.

| Color | Value | Use |
| --- | --- | --- |
| Navy | `#102A43` | Mark background and light-theme lettering |
| Ice | `#F0F7FA` | Mark chevrons and dark-theme lettering |
| Mint | `#32D6BC` | Upper chevron and wordmark accent |

Use the full logo at least 162 px wide and the mark at least 16 px square. Keep clear space of at
least one quarter of the mark's width around either asset. Preserve the aspect ratio and colors;
use `logo-dark.svg` on dark backgrounds. The documentation site selects its variant with a
color-scheme media query; the README uses the PNGs directly.

All SVGs are basic shapes and paths with an accessible title. The favicon and standalone mark
deliberately share the same geometry. Edit both together when changing the mark.
