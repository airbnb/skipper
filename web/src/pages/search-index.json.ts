import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '');
const sectionOrder = ['Getting Started', 'Guides', 'State Machine', 'Reference'];

// Reduce Markdown to searchable plain text. Code is kept (API names are worth searching),
// but fences, links, images, and inline markup are stripped.
function toPlainText(md: string): string {
  return md
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ') // images
    .replace(/```[a-zA-Z0-9]*/g, ' ') // code fences (keep the code text between them)
    .replace(/`+/g, '') // inline-code backticks
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1') // links -> link text
    .replace(/<[^>]+>/g, ' ') // raw HTML tags
    .replace(/^\s*[>#\-*|]+\s?/gm, ' ') // heading/quote/list/table markers at line start
    .replace(/[*_|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export const GET: APIRoute = async () => {
  const docs = await getCollection('docs');

  const items = docs
    .map((d) => {
      const body = d.body ?? '';
      const url = d.id === 'introduction' ? `${base}/docs/` : `${base}/docs/${d.id}/`;
      const headings = [...body.matchAll(/^#{2,3}\s+(.+)$/gm)].map((m) =>
        m[1].replace(/[`*_]/g, '').trim(),
      );
      return {
        title: d.data.title,
        section: d.data.section,
        description: d.data.description ?? '',
        url,
        headings,
        text: toPlainText(body),
      };
    })
    .sort((a, b) => sectionOrder.indexOf(a.section) - sectionOrder.indexOf(b.section));

  return new Response(JSON.stringify(items), {
    headers: { 'Content-Type': 'application/json' },
  });
};
