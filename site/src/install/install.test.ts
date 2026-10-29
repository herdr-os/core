// node --test src/install/install.test.ts  (from site/; uses the real files in ../firmware)
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, openAsBlob, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { debloatList, gunzip, magiskFiles, sha256Hex, stockImages, vbmetaDisabled, verify, type Img } from "./files.ts";
import { type Ctx, dryAdb, dryFastboot, loggedAdb, loggedFastboot } from "./device.ts";
import { flashGsi, unlock, unlockAbility } from "./fastboot-steps.ts";
import { provision, root } from "./adb-steps.ts";
import { asSparse, toRaw } from "./usb.ts";

const REPO = new URL("../../../", import.meta.url).pathname;
const FW = join(REPO, "firmware");
const STOCK_ZIP = join(FW, "rabbit_OS_v0.8.293.zip");
const MAGISK = join(FW, "Magisk-v30.7.apk");
const DOWNLOADS = join(REPO, "site/public/downloads");
const sh = (cmd: string) => execFileSync("bash", ["-c", cmd], { maxBuffer: 1 << 26 });

test("stock zip: the four images, read lazily, byte-identical to unzip", async () => {
  const imgs = await stockImages(await openAsBlob(STOCK_ZIP));
  for (const [name, img] of Object.entries(imgs)) {
    const want = sh(`unzip -p '${STOCK_ZIP}' 'rabbit_OS_v0.8.293_20250516110545/${name}'`);
    assert.equal(img.blob.size, want.length, name);
    assert.equal(await sha256Hex(img.blob), await sha256Hex(new Blob([want])), name);
  }
});

test("vbmeta patch matches lib.sh's vbmeta_disabled byte for byte", async () => {
  const { "vbmeta.img": vb } = await stockImages(await openAsBlob(STOCK_ZIP));
  const ours = new Uint8Array(await (await vbmetaDisabled(vb)).blob.arrayBuffer());
  const dir = mkdtempSync(join(tmpdir(), "vbmeta-"));
  writeFileSync(join(dir, "vbmeta.img"), new Uint8Array(await vb.blob.arrayBuffer()));
  const path = sh(`source '${REPO}tools/lib.sh'; OUT='${dir}/out'; vbmeta_disabled '${dir}/vbmeta.img'`).toString().trim();
  assert.deepEqual(ours, new Uint8Array(readFileSync(path)));
  assert.deepEqual([...ours.subarray(120, 124)], [0, 0, 0, 3]);
  await assert.rejects(vbmetaDisabled({ name: "x.img", blob: new Blob(["nope"]) }), /AVB header/);
});

test("sha256: hosted downloads match manifest.json, a flipped byte does not", async () => {
  const manifest = JSON.parse(readFileSync(join(DOWNLOADS, "manifest.json"), "utf8"));
  for (const name of ["herdr-os.apk", "herdr-os-module.zip", "Magisk-v30.7.apk"]) {
    const f = manifest.files.find((x: { name: string }) => x.name === name);
    const bytes = readFileSync(join(DOWNLOADS, name));
    await verify(new Blob([bytes]), f);
    bytes[100] ^= 1;
    await assert.rejects(verify(new Blob([bytes]), f), /sha256/);
  }
});

test("Magisk files: what root.sh unzips and renames", async () => {
  const names = (await magiskFiles(await openAsBlob(MAGISK))).map((f) => f.name).sort();
  const dir = mkdtempSync(join(tmpdir(), "magisk-"));
  sh(`cd '${dir}' && unzip -q -j '${MAGISK}' 'assets/*.sh' 'assets/stub.apk' 'lib/arm64-v8a/*' && for f in lib*.so; do n=$(basename "$f" .so); mv "$f" "\${n#lib}"; done`);
  assert.deepEqual(names, sh(`ls '${dir}'`).toString().trim().split("\n").sort());
});

test("gunzip streams a .gz and reports progress to 1", async () => {
  const raw = new Uint8Array(3_000_000).map((_, i) => (i * 7) % 251);
  let last = 0;
  const out = await gunzip(new Blob([gzipSync(raw)]), "sample.img.gz", (f) => (last = f));
  assert.equal(out.name, "sample.img");
  assert.deepEqual(new Uint8Array(await out.blob.arrayBuffer()), raw);
  assert.equal(last, 1);
});

test("asSparse: RAW chunks that expand back to the padded image", async () => {
  const size = 64 * 1024 * 1024 + 5000;
  const raw = new Uint8Array(size).map((_, i) => i % 253);
  const sparse = new Uint8Array(await (await asSparse(new Blob([raw]))).arrayBuffer());
  const v = new DataView(sparse.buffer);
  assert.equal(v.getUint32(0, true), 0xed26ff3a);
  const blocks = v.getUint32(16, true), chunks = v.getUint32(20, true);
  assert.equal(blocks * 4096, Math.ceil(size / 4096) * 4096);
  const back: Uint8Array[] = [];
  for (let i = 0, off = 28; i < chunks; i++) {
    assert.equal(v.getUint16(off, true), 0xcac1);
    const len = v.getUint32(off + 8, true) - 12;
    back.push(sparse.subarray(off + 12, off + 12 + len));
    off += 12 + len;
  }
  const joined = new Uint8Array(await new Blob(back).arrayBuffer());
  assert.deepEqual(joined.subarray(0, size), raw);
  assert.ok(joined.subarray(size).every((b) => b === 0));
  const small = new Blob(["vbmeta"]);
  assert.equal(await asSparse(small), small);
});

test("fastboot CLI words become protocol commands", () => {
  assert.equal(toRaw("erase userdata"), "erase:userdata");
  assert.equal(toRaw("snapshot-update cancel"), "snapshot-update:cancel");
  assert.equal(toRaw("delete-logical-partition product_a"), "delete-logical-partition:product_a");
  assert.equal(toRaw("flashing unlock"), "flashing unlock");
  assert.equal(toRaw("flashing get_unlock_ability"), "flashing get_unlock_ability");
});

test("debloat list parses like provision.sh's grep | awk | grep", () => {
  const file = join(REPO, "tools/debloat.txt");
  assert.deepEqual(debloatList(readFileSync(file, "utf8")),
    sh(`grep -v '^\\s*#' '${file}' | awk '{print $1}' | grep .`).toString().trim().split("\n"));
});

/** A dry-run context that records the terminal lines, confirms and saves in order. */
function dry(installed: string[] = [], unlocked = true) {
  const lines: string[] = [];
  const fb = dryFastboot(unlocked), adb = dryAdb(installed);
  const log: Ctx["log"] = (l, kind) => { if (kind === "cmd") lines.push(l); };
  const ctx: Ctx = {
    log,
    confirm: async (title) => { lines.push(`CONFIRM ${title}`); },
    fastboot: async () => loggedFastboot(fb, log),
    adb: async () => loggedAdb(adb, log),
    sleep: async () => {},
    save: (img) => lines.push(`SAVE ${img.name}`),
    progress: () => () => {},
  };
  return { ctx, lines };
}

const vb = (n: string) => [`fastboot flash ${n}_a ${n}-noverify.img`, `fastboot flash ${n}_b ${n}-noverify.img`];

test("dry run: unlock, as INSTALL.md step 1", async () => {
  const { ctx, lines } = dry([], false);
  assert.equal(await unlockAbility(ctx), true);
  await unlock(ctx);
  assert.deepEqual(lines, [
    "fastboot flashing get_unlock_ability",
    "fastboot getvar unlocked",
    "CONFIRM Unlock the bootloader?",
    "fastboot flashing unlock",
    "fastboot getvar unlocked",
  ]);
});

test("dry run: flash-gsi.sh step for step", async () => {
  const { ctx, lines } = dry();
  const stock = await stockImages(await openAsBlob(STOCK_ZIP));
  const gsi: Img = { name: "lineage-21.0-20260614-UNOFFICIAL-arm64_bvN.img", blob: new Blob(["gsi"]) };
  await flashGsi(ctx, stock, gsi);
  assert.deepEqual(lines, [
    "fastboot getvar is-userspace",
    "fastboot getvar unlocked",
    `CONFIRM Flash ${gsi.name}?`,
    ...vb("vbmeta"), ...vb("vbmeta_system"), ...vb("vbmeta_vendor"),
    "fastboot erase userdata",
    "fastboot flash userdata userdata.img",
    "fastboot reboot fastboot",
    "fastboot getvar is-userspace",
    "fastboot snapshot-update cancel",
    "fastboot getvar current-slot",
    "fastboot delete-logical-partition product_a",
    `fastboot flash system_a ${gsi.name}`,
    "fastboot reboot",
  ]);
});

test("dry run: root.sh + flash-boot.sh step for step", async () => {
  const { ctx, lines } = dry();
  const apk: Img = { name: "Magisk-v30.7.apk", blob: await openAsBlob(MAGISK) };
  const pushed = (await magiskFiles(apk.blob)).map((f) => `adb push ${f.name} /data/local/tmp/magisk/${f.name}`);
  await root(ctx, apk);
  const M = "/data/local/tmp/magisk";
  assert.deepEqual(lines, [
    "adb root",
    "adb shell 'id -u'",
    "adb shell 'getprop ro.boot.slot_suffix'",
    "adb push Magisk-v30.7.apk /data/local/tmp/Magisk-v30.7.apk",
    "adb shell 'pm install -r /data/local/tmp/Magisk-v30.7.apk'",
    "adb shell 'rm -f /data/local/tmp/Magisk-v30.7.apk'",
    `adb shell 'rm -rf ${M} && mkdir -p ${M}'`,
    ...pushed,
    `adb shell 'dd if=/dev/block/by-name/boot_a of=${M}/boot.img bs=1M 2>&1 && cd ${M} && chmod 755 * && sh ./boot_patch.sh boot.img 2>&1 && test -s new-boot.img'`,
    `adb pull ${M}/boot.img`,
    `adb pull ${M}/new-boot.img`,
    `adb shell 'rm -rf ${M}'`,
    "SAVE boot_a-unpatched.img",
    "CONFIRM Flash the Magisk-patched boot image to boot_a?",
    "adb reboot bootloader",
    "fastboot getvar is-userspace",
    "fastboot getvar current-slot",
    "fastboot flash boot_a magisk_patched-boot.img",
    "fastboot reboot",
  ]);
});

test("dry run: provision.sh step for step (no --config)", async () => {
  const installed = ["com.android.launcher3", "org.lineageos.jelly", "com.android.systemui"];
  const { ctx, lines } = dry(installed);
  const files = {
    magisk: { name: "Magisk-v30.7.apk", blob: await openAsBlob(MAGISK) },
    app: { name: "herdr-os.apk", blob: new Blob(["apk"]) },
    module: { name: "herdr-os-module.zip", blob: new Blob(["zip"]) },
  };
  const debloat = debloatList(readFileSync(join(REPO, "tools/debloat.txt"), "utf8"));
  await provision(ctx, files, { debloat, density: 190 });
  const bin = (await magiskFiles(files.magisk.blob)).map((f) => `adb push ${f.name} /data/local/tmp/magiskbin/${f.name}`);
  const theme = '{"android.theme.customization.system_palette":"FF4F12","android.theme.customization.accent_color":"FF4F12","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT"}';
  assert.deepEqual(lines, [
    "adb root",
    "adb shell 'id -u'",
    "adb shell id",
    "adb shell 'test -x /data/adb/magisk/busybox && test -f /data/adb/magisk/util_functions.sh'",
    ...bin,
    "adb shell 'mkdir -p /data/adb/modules /data/adb/magisk && chmod 700 /data/adb && cp -af /data/local/tmp/magiskbin/. /data/adb/magisk/ && chown -R 0:0 /data/adb/magisk && chmod -R 755 /data/adb/magisk && rm -rf /data/local/tmp/magiskbin'",
    "adb push herdr-os-module.zip /data/local/tmp/r1ptt-system.zip",
    "adb shell 'magisk --install-module /data/local/tmp/r1ptt-system.zip'",
    "adb push herdr-os.apk /data/local/tmp/herdr-os.apk",
    "adb shell 'pm install -r -g /data/local/tmp/herdr-os.apk'",
    "adb shell 'rm -f /data/local/tmp/herdr-os.apk'",
    "adb shell 'appops set dev.r1ptt SYSTEM_ALERT_WINDOW allow'",
    "adb shell 'pm list packages -U dev.r1ptt'",
    `adb shell 'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(10123,2,0,1,0)"'`,
    "adb shell 'cmd package set-home-activity dev.r1ptt/.herdr.ui.HerdActivity'",
    "adb shell 'logcat -G 2M'",
    "adb shell 'settings put system screen_off_timeout 15000'",
    "adb shell 'settings put system screen_brightness_mode 0'",
    "adb shell 'settings put system screen_brightness 60'",
    "adb shell 'wm density 190'",
    "adb shell 'cmd uimode night yes'",
    "adb shell 'settings put secure lockscreen_use_double_line_clock 0'",
    `adb shell 'settings put secure theme_customization_overlay_packages '\\''${theme}'\\'''`,
    "adb shell 'pm list packages'",
    "adb shell 'pm disable-user --user 0 com.android.launcher3'",
    "adb shell 'pm disable-user --user 0 org.lineageos.jelly'",
    "adb reboot",
  ]);
});

