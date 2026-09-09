# skipper-web

The public website and documentation for **Skipper** — a lightweight, embeddable workflow
engine for durable execution on the JVM. Built with [Astro](https://astro.build) and output as
a fully static site (no server runtime), suitable for static hosting such as GitHub Pages.

The site has two halves: a marketing landing page and a full documentation tree (guides, a
companion State Machine section, and an API reference), with client-side search and a
Kotlin/Java code toggle throughout.

> **Status.** Content is derived from Skipper's internal docs and re-voiced for an external
> audience. The embedded-SQLite default storage backend and the file-backed
> `SqliteWorkflowStore.Factory("skipper.db")` form described in the docs exist in the engine, and so
> does the `WorkflowTest` base class the Testing guide is built on (it replaces the internal-only
> `SkipperTest` harness earlier drafts described).

## Develop

```bash
npm install
npm run dev      # http://localhost:4321 — hot reloads
```

## Build

```bash
npm run build    # static output in ./dist
npm run preview  # serve the built ./dist locally
```

The build emits one HTML file per page plus `dist/search-index.json` (the docs search index).

## Project structure

```
src/
  layouts/
    Layout.astro            # shared <head>, nav, footer shell
    DocsLayout.astro        # docs shell: sidebar + search + code-toggle + prev/next pager
  components/
    Nav.astro               # top navigation (blue-over-hero on the homepage, white elsewhere)
    Footer.astro            # site footer
    Logo.astro              # compass-rose mark (variant: color | white | mono)
    Wordmark.astro          # "Skipper" wordmark (currentColor)
    Icon.astro              # inline stroke-icon set
    LangTabs.astro          # Kotlin / Java code toggle (used on the marketing pages)
  content.config.ts         # `docs` content collection schema (title, description, section, order)
  content/docs/
    *.md                    # Getting Started + Guides pages
    state-machine/*.md       # State Machine section
    reference/*.md           # API Reference section
  pages/
    index.astro             # homepage (hero, value props, code, features, fit, CTAs)
    docs/index.astro        # renders the "introduction" doc at /docs/
    docs/[...slug].astro    # renders every other doc page (incl. state-machine/* and reference/*)
    examples/index.astro    # Examples — runnable sample workflows
    community/index.astro   # Community — contributing, help, license, roadmap
    search-index.json.ts    # build-time JSON search index over the docs collection
  styles/global.css         # design tokens + all component styles
public/
  brand/                    # official Skipper logo lockups & marks
  favicon.svg               # compass mark
```

## How the docs work

Docs pages are **Markdown content-collection entries** in `src/content/docs/`. Frontmatter drives
everything:

```yaml
---
title: Workflow API
description: One-line summary, shown under the page title and used by search.
section: Guides            # Getting Started | Guides | State Machine | Reference
order: 10                  # sort order within the section
---
```

The sidebar is generated from the collection and grouped by `section` in this order:
**Getting Started → Guides → State Machine → Reference**. To add a page, drop a new `.md` file in
the right folder with the right `section`/`order` — no routing changes needed. (`introduction.md`
is special-cased to render at `/docs/`.)

### Kotlin / Java code toggle

Code examples can be shown in either language with a synced toggle:

- On the marketing pages, use the `LangTabs` component.
- In docs Markdown, author a ```kotlin block **immediately followed** by a ```java block (blank
  line only between). A small enhancer in `DocsLayout` merges any such adjacent pair into one
  tabbed widget. Single-language blocks (and the Kotlin-only State Machine pages) render normally.

The chosen language is synced across every snippet on the page and persisted (`localStorage`,
key `skipper-lang`).

### Search

Search is fully client-side and static: `src/pages/search-index.json.ts` emits a JSON index of
every docs page at build time; the search box in the docs sidebar (`DocsLayout`) fetches it lazily
on first focus and ranks results (title > headings > description > body). Press `/` to focus it.

## Sitemap

- **Home** — landing page.
- **Docs** — a single tree in the sidebar:
  - *Getting Started* — Introduction, Quickstart, Core Concepts, Your First Workflow
  - *Guides* — Invoking Workflows, Workflow API, Signals & Queries, Error Handling, Compensation,
    Versioning, Instance Management, Kotlin Coroutines, Storage, Testing, Observability, Troubleshooting
  - *State Machine* — Overview, Your First State Machine, DSL Reference, Persistence & Replay,
    Middleware, Admin UI, Evolution
  - *Reference* — Annotations, Core types, Errors & retries, Configuration
- **Examples** — peer-to-peer transfer, order processing w/ compensation, approval, batch.
- **Community** — contributing, help, license, roadmap.

Top navigation is **Docs · Examples · Community** (the API reference lives inside the docs tree,
Django-style, rather than as a separate top-level destination).

## Branding

The site uses the official **Airbnb Skipper brand kit**:

- **Palette:** blue `#2B388F`, orange `#F05A28`, ink `#222222` (a minimal, nautical three-color scheme).
- **Logomark:** a compass rose. Official SVG lockups live in `public/brand/`; the mark is also
  available inline via `Logo.astro` (`variant="color | white | mono"`) and the wordmark via
  `Wordmark.astro`.
- **Usage:** color/black logo on light backgrounds, white on dark (the footer uses the white mark
  on navy). The homepage hero gradient is blue→blue; orange is reserved for accents and CTAs.

## Deployment

`npm run build` produces a static `./dist` — one HTML file per page (directory format, trailing
slashes: `/docs/quickstart/` → `docs/quickstart/index.html`) plus `dist/search-index.json`. No
server runtime is required; any static host or CDN works. Set the serving origin with
`SKIPPER_SITE_URL` at build time (it defaults to a GitHub Pages project URL):

```bash
SKIPPER_SITE_URL="https://example.com" npm run build
```

The site is built for the S3-origin + CloudFront pattern: a CloudFront viewer function rewrites
`/docs/` → `/docs/index.html`, which is why the build uses `trailingSlash: 'always'` and directory
format (`dist/404.html` doubles as the error document).

## Known follow-ups

- The top-nav links collapse on mobile with no hamburger menu yet (the footer and hero CTAs cover
  navigation in the meantime); the docs sidebar becomes a card on mobile.
- Replace the placeholder GitHub URL and Maven coordinates once the public repo and first release
  exist. The SQLite default and the reified `factory.builder<T>(id)` helper now exist in the engine.
