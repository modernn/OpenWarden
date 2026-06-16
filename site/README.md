# open-warden.com

Marketing site for OpenWarden. Static, zero-dependency, zero-tracker. No build step.

## What it is

- Plain HTML + CSS + a little progressive-enhancement JS. Works fully with JS disabled.
- Fonts are **self-hosted** (`fonts/`), no Google Fonts / no third-party requests.
- No analytics, no cookies, no external scripts. The medium proves the message
  (see [`PRODUCT.md`](../PRODUCT.md) design principle #1).

## Structure

```
site/
  index.html      home (one-page scroll)
  ethics.html     anti-stalkerware stance  -> served at /ethics
  styles.css      the whole design system (OKLCH tokens, layout, motion)
  main.js         sticky header, mobile nav, scroll reveal (enhancement only)
  assets/         favicon.svg
  fonts/          Bricolage Grotesque, Hanken Grotesk, Spline Sans Mono (woff2)
  _headers        Cloudflare Pages security headers + caching + strict CSP
  robots.txt  sitemap.xml  404.html
```

## Local preview

No toolchain needed. Any static server:

```bash
cd site
python -m http.server 8000   # then open http://localhost:8000
```

## Deploy — Cloudflare Pages

This folder is the publish root. There is no build command.

**CLI (direct upload):**
```bash
# CLOUDFLARE_API_TOKEN must be set in the environment
npx wrangler pages deploy site --project-name=open-warden --branch=main
```

**Git integration (recommended, auto-deploy on push):**
In the Cloudflare dashboard, create a Pages project connected to
`github.com/modernn/OpenWarden`:
- Build command: *(none)*
- Build output directory: `site`
- Then add the custom domain `open-warden.com`.

## Editing notes

- Colors are OKLCH custom properties at the top of `styles.css`. The one
  committed accent is `--clay`; affirmations use `--pine`. No `#000`/`#fff`.
- Copy is sourced from repo canon (`README.md`, `docs/PARENT_AS_ADVERSARY.md`,
  `docs/KID_TRANSPARENCY.md`, `docs/ROADMAP.md`). Keep claims traceable to docs.
- GitHub links point at `github.com/modernn/OpenWarden`. Find-and-replace if the
  slug changes.
