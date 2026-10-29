// Node runs the bridge's TypeScript directly, but never inside node_modules, so the published
// package ships plain JavaScript: each file in src/ with its types stripped by Node itself and its
// relative imports pointed at .js. No compiler, no dependencies.
import { mkdir, readdir, readFile, rm, writeFile, chmod, copyFile } from "node:fs/promises";
import { stripTypeScriptTypes } from "node:module";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const src = path.join(root, "src"), out = path.join(root, "dist");
await rm(out, { recursive: true, force: true });

for (const entry of await readdir(src, { recursive: true, withFileTypes: true })) {
  if (!entry.isFile() || !entry.name.endsWith(".ts")) continue;
  const from = path.join(entry.parentPath, entry.name);
  const to = path.join(out, path.relative(src, from)).replace(/\.ts$/, ".js");
  const js = stripTypeScriptTypes(await readFile(from, "utf8"), { mode: "strip" })
    .replace(/(from\s+|import\s*\(\s*)(["'])(\.{1,2}\/[^"']+)\.ts\2/g, "$1$2$3.js$2");
  await mkdir(path.dirname(to), { recursive: true });
  await writeFile(to, js);
}
await chmod(path.join(out, "main.js"), 0o755);
await copyFile(path.join(root, "..", "LICENSE"), path.join(root, "LICENSE"));
console.log("dist/ written");
