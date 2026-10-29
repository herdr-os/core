// Fastboot programs, ported line for line from docs/INSTALL.md step 1, tools/flash-gsi.sh and
// tools/flash-boot.sh. The `fb` each one gets logs every command it sends.
import { type Adb, type Ctx, type Fastboot } from "./device.ts";
import { type Img, type StockImages, vbmetaDisabled } from "./files.ts";

/** lib.sh's in_fastbootd, then back to the bootloader: vbmeta and boot are flashed from there. */
async function bootloader(ctx: Ctx, fb: Fastboot): Promise<Fastboot> {
  if ((await fb.getvar("is-userspace")) !== "yes") return fb;
  ctx.log("In fastbootd; going to the bootloader first");
  await fb.reboot("bootloader");
  await ctx.sleep(5000);
  return ctx.fastboot();
}

async function slot(fb: Fastboot): Promise<string> {
  const s = (await fb.getvar("current-slot")).match(/^[ab]/)?.[0];
  if (!s) throw new Error("Couldn't read the active slot.");
  return s;
}

/** INSTALL.md step 1.4: `fastboot flashing get_unlock_ability` should print 1. */
export async function unlockAbility(ctx: Ctx): Promise<boolean> {
  const fb = await ctx.fastboot();
  const out = await fb.run("flashing get_unlock_ability");
  const ok = /get_unlock_ability:\s*1/.test(out);
  ctx.log(ok
    ? "Rabbit has allowed this R1 to be unlocked."
    : "Not allowed yet. Reboot the R1, let it sit online for a minute, and request the unlock again in OS3.", ok ? "ok" : "warn");
  return ok;
}

/** INSTALL.md step 1.5 and 1.6. */
export async function unlock(ctx: Ctx): Promise<void> {
  const fb = await ctx.fastboot();
  if ((await fb.getvar("unlocked")) === "yes") return ctx.log("Already unlocked.", "ok");
  await ctx.confirm("Unlock the bootloader?",
    "Unlocking ERASES EVERYTHING on the R1 (a factory reset) and permanently voids its warranty. If the R1 asks, confirm with the side button.");
  await fb.run("flashing unlock");
  if ((await fb.getvar("unlocked")) !== "yes") throw new Error("Still locked. If the R1 asked for confirmation, press the side button, then check again.");
  ctx.log("Unlocked.", "ok");
}

/** tools/flash-gsi.sh, without --keep-product. */
export async function flashGsi(ctx: Ctx, stock: StockImages, gsi: Img): Promise<void> {
  const vbmeta = await Promise.all((["vbmeta", "vbmeta_system", "vbmeta_vendor"] as const)
    .map(async (v) => [v, await vbmetaDisabled(stock[`${v}.img`])] as const));

  let fb = await bootloader(ctx, await ctx.fastboot());
  if ((await fb.getvar("unlocked")) !== "yes") throw new Error("Bootloader is locked. Do step 1 first.");

  await ctx.confirm(`Flash ${gsi.name}?`,
    "This ERASES ALL DATA on the R1 (userdata), turns verified boot off (vbmeta on both slots), deletes Rabbit's product partition and replaces the active slot's system with LineageOS.");

  ctx.log("vbmeta with verification off");
  for (const [v, img] of vbmeta) for (const s of ["a", "b"]) await fb.flash(`${v}_${s}`, img);

  ctx.log("Wiping data");
  await fb.run("erase userdata");
  await fb.flash("userdata", stock["userdata.img"]);

  ctx.log("Rebooting to fastbootd");
  await fb.reboot("fastboot");
  await ctx.sleep(5000);
  fb = await ctx.fastboot();
  if ((await fb.getvar("is-userspace")) !== "yes") throw new Error("Didn't reach fastbootd.");
  await fb.run("snapshot-update cancel").catch(() => {});

  const s = await slot(fb);
  ctx.log(`Active slot: ${s}`);
  ctx.log(`Removing stock product_${s}`);
  await fb.run(`delete-logical-partition product_${s}`)
    .catch(() => ctx.log(`product_${s} not deleted (maybe already gone)`, "warn"));

  ctx.log(`Flashing the GSI to system_${s}`);
  await fb.flash(`system_${s}`, gsi, ctx.progress(`system_${s}`));

  ctx.log("Booting LineageOS. First boot takes a few minutes.", "ok");
  await fb.reboot();
}

/** tools/flash-boot.sh: flash a boot image to the active slot only, then reboot. */
export async function flashBoot(ctx: Ctx, img: Img, adb?: Adb): Promise<void> {
  if (adb) {
    ctx.log("Rebooting to the bootloader");
    await adb.reboot("bootloader");
  }
  const fb = await bootloader(ctx, await ctx.fastboot());
  const s = await slot(fb);
  await fb.flash(`boot_${s}`, img);
  ctx.log("Rebooting.", "ok");
  await fb.reboot();
}
