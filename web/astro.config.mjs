// @ts-check
import { defineConfig } from 'astro/config';

// Path prefix the site is served under. '/' suits an origin that serves the site from
// its root (the S3/CloudFront setup); a GitHub Pages *project* site lives under the
// repository name, so .github/workflows/pages.yml sets '/skipper'. Normalised to a
// leading slash with no trailing one, which is the shape `base` wants.
const base = `/${(process.env.SKIPPER_SITE_BASE || '/').replace(/^\/|\/$/g, '')}`;

/**
 * Prefixes root-absolute links and asset references in Markdown with `base`.
 *
 * The docs are authored with root-absolute links (`[Quickstart](/docs/quickstart/)`),
 * which is correct only when the site is served from an origin root. Astro rewrites the
 * assets it resolves itself, but authored `href`/`src` values are opaque to it, so under
 * a base path all 80-odd of those links 404. Rewriting them here keeps the Markdown free
 * of deploy-target details — the alternative is hardcoding the prefix into every
 * document, which would then be wrong for the root-served origin.
 *
 * Left alone: absolute URLs (they have a scheme), protocol-relative `//host/path`,
 * in-page anchors, and anything already under `base`.
 */
function rehypeBasePrefix() {
  // A root-served site is already what the Markdown assumes: nothing to rewrite, and
  // prefixing anyway would turn `/docs/` into the protocol-relative `//docs/`.
  if (base === '/') return () => {};

  const needsPrefix = (value) =>
    typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    value !== base &&
    !value.startsWith(`${base}/`);
  const prefix = (value) => (needsPrefix(value) ? `${base}${value}` : value);

  const walk = (node) => {
    if (node.type === 'element') {
      const props = node.properties || {};
      if (props.href) props.href = prefix(props.href);
      if (props.src) props.src = prefix(props.src);
    }
    for (const child of node.children || []) walk(child);
  };

  return walk;
}

// https://astro.build/config
export default defineConfig({
  // Serving origin. Override with SKIPPER_SITE_URL at build time; the default suits a
  // GitHub Pages project site.
  site: process.env.SKIPPER_SITE_URL || 'https://airbnb.github.io/skipper',
  base,
  // Emit `page/index.html` and always link with a trailing slash. This matches the
  // CloudFront `subdir-index-documents` function, which rewrites `/docs/` to
  // `/docs/index.html` on the S3 origin.
  trailingSlash: 'always',
  build: {
    format: 'directory',
  },
  markdown: {
    shikiConfig: {
      theme: 'github-dark',
    },
    // A no-op when `base` is '/', so the root-served build is unaffected.
    rehypePlugins: [rehypeBasePrefix],
  },
});
