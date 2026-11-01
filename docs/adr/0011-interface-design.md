# 0011. Interface design: glass over ink, one prism accent, a real 3D logo

Status: accepted.

## Context
The first interface was deliberately flat (pure black and white, no gradients). It was readable but looked generic, and the product's name suggests transparency. The app is used for hours, so any redesign must stay legible and fast.

## Decision
- **Look:** dark-first "obsidian" background with slow aurora orbs; frosted-glass surfaces (translucent fill, backdrop blur, gradient hairline border); one prism gradient (cyan, violet, rose) for primary actions, the active navigation pill and progress. Status colours (ok, warn, danger) stay separate so pass/fail is never mistaken for decoration. A light theme swaps the same tokens.
- **Type:** Cormorant Garamond for headlines and large figures, Manrope for the interface, Geist Mono only for code. All bundled, no CDN. Chosen after the first pairing read as generic.
- **Logo:** a glass cube balanced on its corner. SVG everywhere, plus a transparent Fresnel-shaded three.js cube on the splash and sign-in only.
- **3D:** plain three.js, because react-three-fiber 9 and drei 10 need React 19 and the app is on React 18. The cube is a separate lazy chunk, paused when hidden, with an SVG fallback when WebGL is missing or motion is reduced.
- **Motion:** the `motion` library for the shared-element navigation pill and page transitions; everything honours `prefers-reduced-motion` and `prefers-reduced-transparency`.
- **Budget page removed** from the interface: it showed an internal safeguard, not something a user acts on. The estimate and the safety stop remain in the engine.

## Consequences
- Main bundle about 186 KB gzipped (181 KB before the final pages); the cube chunk is about 121 KB gzipped and is not needed after sign-in.
- Contrast of text and accents is checked against WCAG AA by `lib/design.test.ts`.
- Not yet verified: every page at phone width in both themes, and the review queue, experiment detail and charts with real data on screen.
