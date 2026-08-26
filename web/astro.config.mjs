// @ts-check
import { defineConfig } from 'astro/config';

// https://astro.build/config
export default defineConfig({
  // Serving origin. Override with SKIPPER_SITE_URL at build time; the default suits a
  // GitHub Pages project site. Served from the root, so `base` stays '/'.
  site: process.env.SKIPPER_SITE_URL || 'https://airbnb.github.io/skipper',
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
  },
});
