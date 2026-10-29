// Terminal output is hostile input: it may carry OSC hyperlinks, clipboard writes (OSC 52),
// title changes, device queries, or secrets. Everything sent to the R1 passes through here.

const ESC = "\u001b";

export interface SanitizedLine {
  /** Plain text with all escape sequences removed. */
  text: string;
  /** Safe SGR color spans, so the device can colorize without interpreting raw escapes. */
  spans: { start: number; end: number; fg: number | null; bold: boolean }[];
  redacted: boolean;
}

export interface SanitizeResult {
  lines: SanitizedLine[];
  redactions: number;
  neutralized: number;
}

const SECRET_PATTERNS: RegExp[] = [
  /\b(sk-(?:ant-|proj-)?[A-Za-z0-9_-]{16,})\b/g,
  /\b(gh[pousr]_[A-Za-z0-9]{20,})\b/g,
  /\b(xox[abpr]-[A-Za-z0-9-]{10,})\b/g,
  /\b(AKIA[0-9A-Z]{16})\b/g,
  /\b(AIza[0-9A-Za-z_-]{35})\b/g,
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/g,
  /\b(eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b/g,
  /((?:password|passwd|secret|token|api[_-]?key|authorization)\s*[:=]\s*)(["']?)(?!\[redacted\])[^\s"']{6,}\2/gi,
];

export const REDACTED = "[redacted]";

export function redactSecrets(text: string): { text: string; count: number } {
  let count = 0;
  let out = text;
  for (const re of SECRET_PATTERNS) {
    out = out.replace(re, (...m) => {
      count++;
      const prefix = typeof m[1] === "string" && /[:=]\s*$/.test(m[1]) ? m[1] : "";
      return prefix + REDACTED;
    });
  }
  return { text: out, count };
}

/**
 * Strips every escape sequence except SGR (`ESC [ ... m`), which is converted into spans.
 * OSC (hyperlinks, clipboard, titles), DCS/APC/PM, cursor movement and C0 controls are dropped.
 */
export function sanitizeTerminal(raw: string): SanitizeResult {
  let neutralized = 0;
  let redactions = 0;
  const lines: SanitizedLine[] = [];
  for (const rawLine of raw.replace(/\r\n/g, "\n").split("\n")) {
    let text = "";
    const spans: SanitizedLine["spans"] = [];
    let fg: number | null = null;
    let bold = false;
    let spanStart = 0;
    const flush = () => {
      if (text.length > spanStart && (fg !== null || bold)) spans.push({ start: spanStart, end: text.length, fg, bold });
      spanStart = text.length;
    };
    for (let i = 0; i < rawLine.length; i++) {
      const ch = rawLine[i];
      if (ch === ESC) {
        const next = rawLine[i + 1];
        if (next === "[") {
          let j = i + 2;
          while (j < rawLine.length && !/[\x40-\x7e]/.test(rawLine[j])) j++;
          const params = rawLine.slice(i + 2, j);
          if (rawLine[j] === "m" && /^[0-9;]*$/.test(params)) {
            flush();
            [fg, bold] = applySgr(params, fg, bold);
          } else neutralized++;
          i = j;
        } else if (next === "]" || next === "P" || next === "_" || next === "^" || next === "X") {
          // String sequences end at BEL or ST (ESC \).
          let j = i + 2;
          while (j < rawLine.length && rawLine[j] !== "\u0007" && !(rawLine[j] === ESC && rawLine[j + 1] === "\\")) j++;
          i = rawLine[j] === ESC ? j + 1 : j;
          neutralized++;
        } else {
          i += 1;
          neutralized++;
        }
        continue;
      }
      const code = ch.charCodeAt(0);
      if (ch === "\t") text += "    ";
      else if (code < 0x20 || code === 0x7f || (code >= 0x80 && code < 0xa0)) neutralized++;
      else text += ch;
    }
    flush();
    const r = redactSecrets(text);
    const redacted = r.count > 0;
    redactions += r.count;
    // Redaction changes offsets; colors on a redacted line are not worth the risk of misalignment.
    lines.push({ text: r.text, spans: redacted ? [] : spans, redacted });
  }
  return { lines, redactions, neutralized };
}

function applySgr(params: string, fg: number | null, bold: boolean): [number | null, boolean] {
  const codes = params === "" ? [0] : params.split(";").map(Number);
  for (let k = 0; k < codes.length; k++) {
    const c = codes[k];
    if (c === 0) [fg, bold] = [null, false];
    else if (c === 1) bold = true;
    else if (c === 22) bold = false;
    else if (c >= 30 && c <= 37) fg = c - 30;
    else if (c >= 90 && c <= 97) fg = c - 90 + 8;
    else if (c === 39) fg = null;
    else if (c === 38 && codes[k + 1] === 5) {
      fg = codes[k + 2] ?? null;
      k += 2;
    } else if (c === 38 && codes[k + 1] === 2) {
      fg = null; // Truecolor collapses to default; the device palette is fixed.
      k += 4;
    }
  }
  return [fg, bold];
}

/** Drops trailing blank rows and the box-drawing chrome of agent TUIs so the compact view shows content. */
export function compactLines(lines: SanitizedLine[]): SanitizedLine[] {
  const out = lines.filter((l) => !/^[\s─━═╭╮╰╯│┃┌┐└┘├┤┬┴┼▔▁]*$/.test(l.text) || l.text === "");
  while (out.length && out[out.length - 1].text.trim() === "") out.pop();
  const collapsed: SanitizedLine[] = [];
  for (const l of out) {
    if (l.text.trim() === "" && collapsed.length && collapsed[collapsed.length - 1].text.trim() === "") continue;
    collapsed.push(l);
  }
  return collapsed;
}
