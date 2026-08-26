import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// Docs pages are authored as Markdown in src/content/docs/. The filename
// (without extension) becomes the entry id and the URL slug.
const docs = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/docs' }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    section: z.string(),
    order: z.number().default(0),
  }),
});

export const collections = { docs };
