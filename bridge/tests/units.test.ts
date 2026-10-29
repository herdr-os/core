import { test } from "node:test";
import assert from "node:assert/strict";
import { parseDecision } from "../src/decisions/parse.ts";
import { sanitizeTerminal, compactLines } from "../src/terminal/sanitize.ts";

test("parses a Claude Code permission dialog", () => {
  const d = parseDecision(
    [
      "╭───────────────────────────────────────────────╮",
      "│ Edit file                                     │",
      "│ src/api/users.ts                              │",
      "│ Do you want to make this edit to users.ts?    │",
      "│ ❯ 1. Yes                                      │",
      "│   2. Yes, allow all edits during this session (shift+tab) │",
      "│   3. No, and tell Claude what to do differently (esc) │",
      "╰───────────────────────────────────────────────╯",
      "",
    ].join("\n"),
  );
  assert.equal(d.question, "Do you want to make this edit to users.ts?");
  assert.deepEqual(d.options.map((o) => o.label), ["Yes", "Yes, allow all edits during this session", "No, and tell Claude what to do differently"]);
  assert.equal(d.parse, "structured");
});

test("parses a Codex approval", () => {
  const d = parseDecision("Would you like to run the following command?\n\n  $ pnpm test\n\n› 1. Yes, proceed (y)\n  2. No, and tell Codex what to do differently (esc)\n");
  assert.equal(d.options.length, 2);
  assert.equal(d.options[0].key, "1");
  assert.equal(d.options[0].label, "Yes, proceed");
});

test("prose question with no options is free text", () => {
  const d = parseDecision("I found two failing tests.\nShould I update the snapshot or fix the code?\n");
  assert.equal(d.parse, "free_text");
  assert.equal(d.options.length, 0);
  assert.equal(d.question, "Should I update the snapshot or fix the code?");
});

test("same screen gives the same fingerprint; any change gives a new one", () => {
  const a = parseDecision("Proceed?\n❯ 1. Yes\n  2. No");
  const b = parseDecision("Proceed?\n❯ 1. Yes\n  2. No");
  const c = parseDecision("Delete prod?\n❯ 1. Yes\n  2. No");
  assert.equal(a.fingerprint, b.fingerprint);
  assert.notEqual(a.fingerprint, c.fingerprint);
});

test("sanitizer drops OSC, DCS, cursor movement and C0 controls", () => {
  const r = sanitizeTerminal("a\u001b[2Jb\u001bP1$r\u001b\\c\u0007d\u001b]0;title\u001b\\e\u001b[1;32mok\u001b[0m");
  assert.equal(r.lines[0].text, "abcdeok");
  assert.deepEqual(r.lines[0].spans, [{ start: 5, end: 7, fg: 2, bold: true }]);
  assert.equal(r.neutralized, 4);
});

test("compact view strips TUI box chrome and trailing blanks", () => {
  const r = compactLines(sanitizeTerminal("╭────╮\n│ hi │\n╰────╯\n\n\nnext\n\n").lines);
  assert.deepEqual(r.map((l) => l.text), ["│ hi │", "", "next"]);
});

test("pairing link carries the bridge origin and the one-time code", async () => {
  const { pairingLink } = await import("../src/auth/devices.ts");
  assert.equal(pairingLink("https://bridge.tail.ts.net:8443/", "4821-0937"), "herdr://pair?u=https%3A%2F%2Fbridge.tail.ts.net%3A8443&c=4821-0937");
  assert.equal(pairingLink("http://100.80.68.69:8787", "1-2", ["read", "act"], "2026-10-29T10:00:00.000Z"),
    "herdr://pair?u=http%3A%2F%2F100.80.68.69%3A8787&c=1-2&s=read%2Cact&x=2026-10-29T10%3A00%3A00.000Z");
  assert.throws(() => pairingLink("ftp://x", "1"));
});
