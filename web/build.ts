#!/usr/bin/env bun
// Production build: bundle src/index.html (tailwind plugin picks up globals.css), copy the
// favicon, then SYMLINK the heavyweight runtime assets out of ../public — the jars are
// ~110 MB, so dist must reference them, never contain them.
import tailwind from "bun-plugin-tailwind";
import { existsSync } from "fs";
import { cp, rm, symlink } from "fs/promises";
import path from "path";

const root = import.meta.dir;
const dist = path.join(root, "dist");

await rm(dist, { recursive: true, force: true });

const result = await Bun.build({
  entrypoints: [path.join(root, "src", "index.html")],
  outdir: dist,
  plugins: [tailwind],
  minify: true,
  sourcemap: "linked",
  define: { "process.env.NODE_ENV": JSON.stringify("production") },
});

if (!result.success) {
  for (const log of result.logs) console.error(log);
  process.exit(1);
}

await cp(path.join(root, "src", "favicon.svg"), path.join(dist, "favicon.svg"));

// Relative targets, resolved from inside dist/: dist/jars -> ../public/jars, etc.
// world.zip is optional (only present when a seed world has been baked).
const links: Array<[target: string, name: string, optional: boolean]> = [
  ["../public/jars", "jars", false],
  ["../public/classpath.txt", "classpath.txt", false],
  ["../public/world.zip", "world.zip", true],
];
for (const [target, name, optional] of links) {
  if (optional && !existsSync(path.join(dist, target))) continue;
  await symlink(target, path.join(dist, name));
}

console.log(`built ${result.outputs.length} files into ${dist}`);
