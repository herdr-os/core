// adb programs, ported line for line from tools/root.sh and tools/provision.sh. Like the scripts
// (set -e), a command that fails stops the step unless the script tolerates it.
import { type Adb, type Ctx, type ShellResult } from "./device.ts";
import { type Img, magiskFiles } from "./files.ts";
import { flashBoot } from "./fastboot-steps.ts";

export const PKG = "dev.r1ptt";

function must(r: ShellResult): ShellResult {
  if (r.code !== 0) throw new Error(`Exit ${r.code}${r.out.trim() ? `: ${r.out.trim().split("\n").slice(-3).join(" ")}` : ""}`);
  return r;
}

/** `adb install <flags>`: push to /data/local/tmp, then pm install, as adb's own install does. */
async function install(adb: Adb, apk: Img, flags: string, ctx: Ctx): Promise<void> {
  const remote = `/data/local/tmp/${apk.name}`;
  await adb.push(apk, remote, ctx.progress(apk.name));
  const r = await adb.shell(`pm install ${flags} ${remote}`);
  await adb.shell(`rm -f ${remote}`);
  if (r.code !== 0 || !/Success/.test(r.out)) throw new Error(`Couldn't install ${apk.name}`);
}

/** `adb root` then `adb wait-for-device`. */
async function adbRoot(ctx: Ctx): Promise<Adb> {
  const adb = await ctx.adb();
  return (await adb.root()) ? ctx.adb() : adb;
}

/** tools/root.sh. */
export async function root(ctx: Ctx, magiskApk: Img): Promise<void> {
  const files = await magiskFiles(magiskApk.blob);
  const adb = await adbRoot(ctx);
  if ((await adb.shell("id -u")).out.trim() !== "0")
    throw new Error("adb root isn't available on this build (enable Rooted debugging in Developer options).");
  const slot = (await adb.shell("getprop ro.boot.slot_suffix")).out.trim();
  if (!slot) throw new Error("Couldn't read the active slot.");

  ctx.log("Installing the Magisk app");
  await install(adb, magiskApk, "-r", ctx);

  ctx.log(`Patching boot${slot} with Magisk's own script, on the R1`);
  const dir = "/data/local/tmp/magisk";
  must(await adb.shell(`rm -rf ${dir} && mkdir -p ${dir}`));
  for (const f of files) await adb.push(f, `${dir}/${f.name}`);
  const patch = await adb.shell(`dd if=/dev/block/by-name/boot${slot} of=${dir}/boot.img bs=1M 2>&1 && cd ${dir} && chmod 755 * && sh ./boot_patch.sh boot.img 2>&1 && test -s new-boot.img`);
  if (patch.code !== 0) throw new Error("Magisk couldn't patch the boot image.");

  const unpatched: Img = { name: `boot${slot}-unpatched.img`, blob: await adb.pull(`${dir}/boot.img`) };
  const patched: Img = { name: "magisk_patched-boot.img", blob: await adb.pull(`${dir}/new-boot.img`) };
  must(await adb.shell(`rm -rf ${dir}`));
  ctx.save(unpatched);
  ctx.log(`Keep ${unpatched.name}: flashing it back to boot${slot} unroots.`, "ok");

  await ctx.confirm(`Flash the Magisk-patched boot image to boot${slot}?`,
    `This overwrites boot${slot} (the R1's kernel image) with the rooted copy. No data is erased; ${unpatched.name}, just saved, puts the original back.`);
  await flashBoot(ctx, patched, adb);
}

export interface ProvisionOptions { debloat: string[] | null; density: number }

/** tools/provision.sh, minus --config: speech keys are typed on the R1 later. */
export async function provision(ctx: Ctx, files: { magisk: Img; app: Img; module: Img }, opts: ProvisionOptions): Promise<void> {
  const adb = await adbRoot(ctx);
  const asRoot = (await adb.shell("id -u")).out.trim() === "0";
  const rootsh = (cmd: string) => adb.shell(asRoot ? cmd : `su -c '${cmd}'`);

  ctx.log("Checking root (approve the Magisk prompt for Shell on the R1 if one appears)");
  for (let i = 1; !/uid=0/.test((await rootsh("id")).out); i++) {
    if (i === 6) throw new Error("No root over adb. Open Magisk, Superuser, and allow Shell, then run this step again.");
    await ctx.sleep(5000);
  }

  if ((await rootsh("test -x /data/adb/magisk/busybox && test -f /data/adb/magisk/util_functions.sh")).code !== 0) {
    ctx.log(`Finishing Magisk's setup from ${files.magisk.name}`);
    for (const f of await magiskFiles(files.magisk.blob)) await adb.push(f, `/data/local/tmp/magiskbin/${f.name}`);
    must(await rootsh("mkdir -p /data/adb/modules /data/adb/magisk && chmod 700 /data/adb && cp -af /data/local/tmp/magiskbin/. /data/adb/magisk/ && chown -R 0:0 /data/adb/magisk && chmod -R 755 /data/adb/magisk && rm -rf /data/local/tmp/magiskbin"));
  }

  ctx.log("Installing the Magisk module (button remap + power defaults)");
  await adb.push(files.module, "/data/local/tmp/r1ptt-system.zip", ctx.progress(files.module.name));
  must(await rootsh("magisk --install-module /data/local/tmp/r1ptt-system.zip"));

  ctx.log("Installing the app");
  await install(adb, files.app, "-r -g", ctx);
  must(await adb.shell(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`));

  ctx.log("Granting the app root without a prompt");
  const uid = (await adb.shell(`pm list packages -U ${PKG}`)).out.match(/^package:dev\.r1ptt uid:(\d+)/m)?.[1];
  if (!uid) throw new Error("Couldn't find the app's uid.");
  must(await rootsh(`magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(${uid},2,0,1,0)"`));

  ctx.log("Making it the home screen (preserving Android lock and administrator settings)");
  must(await adb.shell(`cmd package set-home-activity ${PKG}/.herdr.ui.HerdActivity`));

  ctx.log("Keeping more Android log history");
  if ((await adb.shell("logcat -G 2M")).code !== 0) ctx.log("Couldn't resize the log buffers; they grow after the next reboot.", "warn");

  ctx.log("Display defaults");
  must(await adb.shell("settings put system screen_off_timeout 15000"));
  must(await adb.shell("settings put system screen_brightness_mode 0"));
  must(await adb.shell("settings put system screen_brightness 60"));
  if (opts.density !== 0) must(await adb.shell(`wm density ${opts.density}`));
  const theme = '{"android.theme.customization.system_palette":"FF4F12","android.theme.customization.accent_color":"FF4F12","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT"}';
  must(await adb.shell("cmd uimode night yes"));
  must(await adb.shell("settings put secure lockscreen_use_double_line_clock 0"));
  must(await adb.shell(`settings put secure theme_customization_overlay_packages '${theme}'`));

  if (opts.debloat) {
    ctx.log("Disabling unneeded system apps (reversible with pm enable)");
    const installed = new Set((await adb.shell("pm list packages")).out.split("\n").map((l) => l.trim().replace(/^package:/, "")));
    for (const p of opts.debloat.filter((p) => installed.has(p))) {
      const r = await adb.shell(`pm disable-user --user 0 ${p}`);
      ctx.log(r.code === 0 ? `disabled ${p}` : `could not disable ${p}`, r.code === 0 ? undefined : "warn");
    }
  }

  ctx.log("Skipping speech keys: you type them on the R1, in Settings, after pairing.");
  ctx.log("Rebooting to apply the module");
  await adb.reboot();
  ctx.log("Done. After it boots, pair it with your bridge (step 5).", "ok");
}
