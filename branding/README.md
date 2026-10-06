# Axiom branding

| File | Use |
| --- | --- |
| `logo.svg` | Mark and wordmark for light backgrounds (README, documentation). |
| `logo-dark.svg` | Mark and wordmark for dark backgrounds. |
| `logo-mark.svg` | The mark alone: avatars, social previews, slide corners. |
| `favicon.svg` | Browser tab icon; the mark reads down to 16 px. |

The mark is a geometric A with an open counter and a mint crossbar. Its broad legs and flat colors
keep it legible at small sizes. The wordmark is custom vector lettering: no installed font or
external resource is required.

| Color | Value | Use |
| --- | --- | --- |
| Navy | `#102A43` | Mark background and light-theme lettering |
| Ice | `#F0F7FA` | Mark legs and dark-theme lettering |
| Mint | `#32D6BC` | Crossbar |

Use the full logo at least 162 px wide and the mark at least 16 px square. Keep clear space of at
least one quarter of the mark's width around either asset. Preserve the aspect ratio and colors;
use `logo-dark.svg` on dark backgrounds. The README selects its variant with a `picture` element,
and the documentation site selects it with a color-scheme media query.

All assets are SVG paths and basic shapes with an accessible title. The favicon and standalone
mark deliberately share the same geometry. Edit both together when changing the mark.
