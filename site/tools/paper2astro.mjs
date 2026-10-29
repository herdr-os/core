// Turns a Paper artboard's JSX (from get_jsx, inline styles, no node ids) into an Astro component.
// Assets on Paper's file store are downloaded into public/paper/ and the fonts point at the
// self-hosted variable fonts. Usage: node tools/paper2astro.mjs paper-src/asleep.jsx src/screens/Asleep.astro
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const [src, out] = process.argv.slice(2);
if (!src || !out) throw new Error("usage: paper2astro <in.jsx> <out.astro>");
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");

let jsx = await readFile(src, "utf8");
// get_jsx wraps the tree in "( ... )", sometimes after a JSON header.
const open = jsx.indexOf("(\n");
jsx = jsx.slice(open >= 0 ? open + 1 : 0, jsx.lastIndexOf(")")).trim();

const assets = new Set(jsx.match(/https:\/\/app\.paper\.design\/file-assets\/[^)'"\s]+/g) ?? []);
await mkdir(path.join(root, "public/paper"), { recursive: true });
for (const url of assets) {
  const name = url.split("/").pop();
  const file = path.join(root, "public/paper", name);
  if (!existsSync(file)) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${res.status} ${url}`);
    await writeFile(file, Buffer.from(await res.arrayBuffer()));
  }
  jsx = jsx.replaceAll(url, `/paper/${name}`);
}

jsx = jsx.replaceAll('"Space Grotesk"', '"Space Grotesk Variable"').replaceAll('"JetBrains Mono"', '"JetBrains Mono Variable"');
// Astro adds the leading dash React would: keep vendor properties working.
jsx = jsx.replace(/\b(Moz|Webkit)([A-Z]\w*):/g, (_, v, rest) => `'-${v.toLowerCase()}-${rest.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase()).slice(1)}':`);

// Paper writes some lengths as bare numbers; Astro, unlike React, adds no unit.
const LENGTH = /\b(left|top|right|bottom|width|height|minWidth|minHeight|maxWidth|maxHeight|gap|rowGap|columnGap|padding\w*|margin\w*|inset|fontSize|lineHeight|borderRadius|borderWidth|outlineOffset)(\s*:\s*)(-?\d+(?:\.\d+)?)(?=\s*[,}])/g;
jsx = jsx.replace(LENGTH, (_, k, sep, n) => `${k}${sep}'${n}px'`);

await mkdir(path.dirname(out), { recursive: true });
await writeFile(out, `---\n// Generated from Paper by tools/paper2astro.mjs; regenerate rather than edit.\n---\n${jsx}\n`);
console.log(`${out}: ${assets.size} asset(s)`);
