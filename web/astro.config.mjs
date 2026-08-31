// @ts-check
import { defineConfig } from 'astro/config';

// https://astro.build/config
export default defineConfig({
  // Serving origin. Override with SKIPPER_SITE_URL at build time. The default is the
  // GitHub Pages custom domain declared in public/CNAME; because the site is served from
  // that domain's root rather than from a /<repo>/ project path, `base` stays '/' and the
  // root-absolute links in the docs markdown are correct as authored.
  site: process.env.SKIPPER_SITE_URL || 'https://skipper.airbnb.tech',
  // Emit `page/index.html` and always link with a trailing slash. GitHub Pages resolves a
  // directory request to its index.html, so `/docs/` serves `/docs/index.html` natively.
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
