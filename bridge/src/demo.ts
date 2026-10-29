import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import path from "node:path";
import { git } from "./diff/git.ts";

// Small real Git repositories for `--fixtures`, so the diff/review path runs against Git rather
// than canned JSON.

const FILES: Record<string, Record<string, [string, string]>> = {
  "backend-api": {
    "src/api/users.ts": [
      "export async function createUser(req, res) {\n  const user = await db.users.insert(req.body);\n  return res.status(200).json({\n    user,\n  });\n}\n",
      "export async function createUser(req, res) {\n  const user = await db.users.insert(req.body);\n  return res.status(201).json({\n    user,\n  });\n}\n",
    ],
    "src/api/auth.ts": ["export const SESSION_TTL = 3600;\n", "export const SESSION_TTL = 3600;\n"],
    "src/db/schema.ts": [
      "export const users = table('users', {\n  id: serial(),\n  email: text(),\n});\n",
      "export const users = table('users', {\n  id: serial(),\n  email: text().unique(),\n  created_at: timestamp().defaultNow(),\n});\n",
    ],
    ".env": ["DATABASE_URL=postgres://localhost/dev\n", "DATABASE_URL=postgres://localhost/dev\n"],
  },
  "tests-qa": {
    "tests/auth.test.ts": ["", "import { login } from '../src/auth';\n\ntest('rejects expired sessions', async () => {\n  expect(await login({ ttl: -1 })).toBe(null);\n});\n"],
  },
  "mobile-app": {
    "src/components/Card.tsx": ["export const Card = () => null;\n", "export const Card = () => null;\n"],
  },
};

export async function makeDemoRepos(root: string): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const [name, files] of Object.entries(FILES)) {
    const dir = path.join(root, name);
    out[name] = dir;
    if (existsSync(path.join(dir, ".git"))) continue;
    mkdirSync(dir, { recursive: true });
    await git(dir, ["init", "-q", "-b", "main"]);
    for (const [p, [before]] of Object.entries(files)) {
      if (!before) continue;
      mkdirSync(path.dirname(path.join(dir, p)), { recursive: true });
      writeFileSync(path.join(dir, p), before);
    }
    await git(dir, ["add", "-A"]);
    await git(dir, ["-c", "user.name=herdr-demo", "-c", "user.email=demo@herdr.invalid", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "baseline"]);
    for (const [p, [, after]] of Object.entries(files)) {
      mkdirSync(path.dirname(path.join(dir, p)), { recursive: true });
      writeFileSync(path.join(dir, p), after);
    }
  }
  return out;
}
