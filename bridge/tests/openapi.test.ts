import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const server = readFileSync(new URL("../src/api/server.ts", import.meta.url), "utf8");
const spec = readFileSync(new URL("../../protocol/openapi.yaml", import.meta.url), "utf8");

/** `{ "/v1/x": Set{"get","post"} }` from the spec's top-level `paths:` block. */
function specRoutes() {
  const out = new Map<string, Set<string>>();
  let current: string | null = null;
  for (const line of spec.slice(spec.indexOf("\npaths:")).split("\n")) {
    const path = /^  (\/v1\/\S+):\s*$/.exec(line);
    if (path) out.set((current = path[1]), new Set());
    const method = /^    (get|post|put|delete|patch):/.exec(line);
    if (method && current) out.get(current)!.add(method[1]);
  }
  return out;
}

test("every bridge route is in protocol/openapi.yaml", () => {
  const routes = [...server.matchAll(/route\("(GET|POST)", "([^"]+)"/g)].map((m) => [m[1].toLowerCase(), m[2]]);
  routes.push(["get", "/v1/events"]);
  assert.ok(routes.length > 20, "found the route table");
  const documented = specRoutes();
  const missing = routes.filter(([method, path]) => !documented.get(path)?.has(method)).map(([m, p]) => `${m.toUpperCase()} ${p}`);
  assert.deepEqual(missing, []);
});
