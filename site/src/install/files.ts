// Everything the installer reads: hosted downloads (sha256-checked), images pulled lazily out of the
// stock zip and the Magisk APK, the gunzipped GSI, and the vbmeta edit from tools/lib.sh.
import { BlobReader, BlobWriter, ZipReader, configure } from "@zip.js/zip.js";

configure({ useWebWorkers: false });

/** A named image or file, the unit every step passes around. */
export interface Img { name: string; blob: Blob }

export type Progress = (fraction: number) => void;

interface ManifestFile { name: string; url: string; sha256: string; bytes: number; what: string }
export interface Manifest { version: string; built: string; files: ManifestFile[] }

export async function sha256Hex(blob: Blob): Promise<string> {
  if (!crypto.subtle) throw new Error("Checking downloads needs a secure page: open the installer over HTTPS.");
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Throws unless the blob is exactly the bytes the manifest promises. */
export async function verify(blob: Blob, f: Pick<ManifestFile, "name" | "sha256" | "bytes">): Promise<void> {
  if (blob.size !== f.bytes) throw new Error(`${f.name}: ${blob.size} bytes, expected ${f.bytes}`);
  const got = await sha256Hex(blob);
  if (got !== f.sha256) throw new Error(`${f.name}: sha256 ${got} does not match the manifest's ${f.sha256}`);
}

let manifest: Promise<Manifest> | undefined;
export function getManifest(base = "/downloads/manifest.json"): Promise<Manifest> {
  manifest ??= fetch(base, { cache: "no-cache" }).then((r) => {
    if (!r.ok) throw new Error(`manifest.json: HTTP ${r.status}`);
    return r.json() as Promise<Manifest>;
  });
  return manifest;
}

/** Downloads one hosted file and checks its sha256 before anything uses it. */
export async function download(name: string): Promise<Img> {
  const f = (await getManifest()).files.find((x) => x.name === name);
  if (!f) throw new Error(`${name} is not in manifest.json`);
  const r = await fetch(f.url);
  if (!r.ok) throw new Error(`${f.url}: HTTP ${r.status}`);
  const blob = await r.blob();
  await verify(blob, f);
  return { name, blob };
}

/**
 * Extracts the entries `pick` names (it returns the output name, or null to skip). zip.js reads the
 * central directory and each picked entry through Blob.slice, so a 900 MB zip never loads whole.
 */
export async function unzip(zip: Blob, pick: (path: string) => string | null): Promise<Img[]> {
  const reader = new ZipReader(new BlobReader(zip));
  try {
    const out: Img[] = [];
    for (const e of await reader.getEntries()) {
      const name = e.directory ? null : pick(e.filename);
      if (name) out.push({ name, blob: await e.getData!(new BlobWriter()) });
    }
    return out;
  } finally {
    await reader.close();
  }
}

const base = (p: string) => p.slice(p.lastIndexOf("/") + 1);

export const STOCK_IMAGES = ["vbmeta.img", "vbmeta_system.img", "vbmeta_vendor.img", "userdata.img"] as const;
export type StockImages = Record<(typeof STOCK_IMAGES)[number], Img>;

/** The four images flash-gsi.sh takes from the extracted rabbitOS zip (it skips __MACOSX too). */
export async function stockImages(zip: Blob): Promise<StockImages> {
  const want = new Set<string>(STOCK_IMAGES);
  const found = await unzip(zip, (p) => (!p.includes("__MACOSX") && want.has(base(p)) ? base(p) : null));
  const byName = Object.fromEntries(found.map((i) => [i.name, i]));
  const missing = STOCK_IMAGES.filter((n) => !byName[n]);
  if (missing.length) throw new Error(`Not a rabbitOS firmware zip: no ${missing.join(", ")}`);
  return byName as StockImages;
}

/**
 * What root.sh and provision.sh unzip from the Magisk APK (`assets/*.sh assets/stub.apk
 * lib/arm64-v8a/*`, flattened), with lib<name>.so renamed to <name>.
 */
export async function magiskFiles(apk: Blob): Promise<Img[]> {
  return unzip(apk, (p) => {
    const b = base(p);
    if (p.startsWith("assets/") && (b.endsWith(".sh") || p === "assets/stub.apk")) return b;
    if (p.startsWith("lib/arm64-v8a/")) return /^lib.*\.so$/.test(b) ? b.slice(3, -3) : b;
    return null;
  });
}

/** Streams a .gz through DecompressionStream into a Blob, reporting progress over the input. */
export async function gunzip(file: Blob, name: string, onProgress: Progress = () => {}): Promise<Img> {
  let read = 0;
  const count = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, c) {
      read += chunk.byteLength;
      onProgress(read / file.size);
      c.enqueue(chunk);
    },
  });
  const out = file.stream().pipeThrough(count).pipeThrough(new DecompressionStream("gzip"));
  const img = name.replace(/\.gz$/, "");
  // 2.5 GB is past what Chrome keeps as an in-memory Blob, so it goes to the origin's private
  // file system on disk (dropScratch removes it). Node, in the tests, has no OPFS.
  if (!globalThis.navigator?.storage?.getDirectory) return { name: img, blob: await new Response(out).blob() };
  const isize = new DataView(await file.slice(-4).arrayBuffer()).getUint32(0, true); // size mod 4 GiB, from the gzip trailer
  const { quota = 0, usage = 0 } = await navigator.storage.estimate();
  if (quota - usage < isize) {
    await out.cancel();
    throw new Error(`Decompressing needs ${(isize / 1e9).toFixed(1)} GB of browser storage and this window allows ${((quota - usage) / 1e9).toFixed(1)} GB. Use a normal (not incognito or guest) Chrome window with free disk space.`);
  }
  const handle = await (await navigator.storage.getDirectory()).getFileHandle(SCRATCH, { create: true });
  await out.pipeTo(await handle.createWritable());
  return { name: img, blob: await handle.getFile() };
}

const SCRATCH = "gsi.img";

export async function dropScratch(): Promise<void> {
  await (await navigator.storage?.getDirectory?.())?.removeEntry(SCRATCH).catch(() => {});
}

/**
 * lib.sh's vbmeta_disabled: check the AVB0 magic, then set the header flags (big-endian u32 at byte
 * 120) to 3, HASHTREE_DISABLED | VERIFICATION_DISABLED.
 */
export async function vbmetaDisabled(img: Img): Promise<Img> {
  const bytes = new Uint8Array(await img.blob.arrayBuffer());
  if (new TextDecoder().decode(bytes.subarray(0, 4)) !== "AVB0") throw new Error(`${img.name} doesn't start with an AVB header`);
  bytes.set([0, 0, 0, 3], 120);
  return { name: img.name.replace(/\.img$/, "-noverify.img"), blob: new Blob([bytes]) };
}

/** tools/debloat.txt as provision.sh reads it: drop comment lines, first word of each line. */
export function debloatList(text: string): string[] {
  return text.split("\n").filter((l) => !/^\s*#/.test(l)).map((l) => l.trim().split(/\s+/)[0]).filter(Boolean);
}

/** Hands the browser a file to save (the unpatched boot image, for unrooting). */
export function save(img: Img): void {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(img.blob);
  a.download = img.name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
}
