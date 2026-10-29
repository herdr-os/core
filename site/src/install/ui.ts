// The page: device mode, the wizard, logs, confirms, dry run. Steps live in *-steps.ts.
import debloatText from "./debloat.txt?raw";
import { provision, root } from "./adb-steps.ts";
import { type Ctx, Cancelled, dryAdb, dryFastboot, loggedAdb, loggedFastboot } from "./device.ts";
import { flashGsi, unlock, unlockAbility } from "./fastboot-steps.ts";
import { debloatList, download, dropScratch, gunzip, save, stockImages, type Img } from "./files.ts";
import { enterFastboot, isPreloader } from "./serial.ts";
import { ADB_FILTER, FASTBOOT_FILTER, openAdb, openFastboot, usbMode, waitForUsb } from "./usb.ts";

const $ = <T extends Element = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<T>(sel)!;
const $$ = <T extends Element = HTMLElement>(sel: string) => [...document.querySelectorAll<T>(sel)];

const DEBLOAT = debloatList(debloatText);
const usb = "usb" in navigator ? navigator.usb : undefined;
const serial = "serial" in navigator ? navigator.serial : undefined;

type Mode = "none" | "preloader" | "fastboot" | "fastbootd" | "adb";
const MODES: Record<Mode, [string, string]> = {
  none: ["No R1", "Plug it in, then press “Choose the R1”. Chrome only shows this page the devices you pick."],
  preloader: ["Preloader", "Starting up. This lasts about 1.5 s; the page answers it with FASTBOOT."],
  fastboot: ["Fastboot", "The bootloader. Unlock, vbmeta, userdata and boot are written here."],
  fastbootd: ["Fastbootd", "Android's userspace fastboot. The system partition is written here."],
  adb: ["Android", "Booted, with USB debugging (adb). Root and Herdr OS install from here."],
};

let busy = false;
let dry = !usb;

function setMode(mode: Mode) {
  const [name, says] = MODES[mode];
  $("[data-mode]").textContent = name;
  $("[data-mode-says]").textContent = says;
  $("[data-screen-mode]").textContent = name;
  document.documentElement.dataset.device = mode;
}

async function refreshMode() {
  if (busy) return;
  const devices = usb ? await usb.getDevices() : [];
  const fb = devices.find((d) => usbMode(d) === "fastboot");
  if (fb) {
    try {
      const userspace = (await (await openFastboot(fb)).getvar("is-userspace")) === "yes";
      return setMode(userspace ? "fastbootd" : "fastboot");
    } catch {
      return setMode("fastboot");
    }
  }
  if (devices.some((d) => usbMode(d) === "adb")) return setMode("adb");
  if (serial && (await serial.getPorts()).some(isPreloader)) return setMode("preloader");
  setMode("none");
}

function confirmDialog(title: string, erases: string): Promise<void> {
  const dialog = $<HTMLDialogElement>("#confirm");
  $("[data-confirm-title]", dialog).textContent = title;
  $("[data-confirm-erases]", dialog).textContent = erases;
  $("[data-confirm-dry]", dialog).hidden = !dry;
  dialog.showModal();
  return new Promise((resolve, reject) => {
    dialog.addEventListener("close", () => (dialog.returnValue === "yes" ? resolve() : reject(new Cancelled())), { once: true });
  });
}

function makeCtx(panel: HTMLElement, signal: AbortSignal, unlocked: boolean): Ctx {
  const out = $("[data-log]", panel);
  const bar = $("[data-progress]", panel);
  const log: Ctx["log"] = (line, kind) => {
    const el = document.createElement("div");
    el.className = kind ?? "out";
    el.textContent = kind === "cmd" ? `$ ${line}${dry ? "   # dry run, not sent" : ""}` : line;
    out.append(el);
    out.scrollTop = out.scrollHeight;
  };
  const dryFb = dryFastboot(unlocked);
  const dryA = dryAdb(DEBLOAT);
  return {
    log,
    confirm: confirmDialog,
    async fastboot() {
      if (dry) return loggedFastboot(dryFb, log);
      log("Waiting for the R1 in fastboot mode (up to 90 s)…");
      const fb = await openFastboot(await waitForUsb("fastboot", signal));
      setMode((await fb.getvar("is-userspace")) === "yes" ? "fastbootd" : "fastboot");
      return loggedFastboot(fb, log);
    },
    async adb() {
      if (dry) return loggedAdb(dryA, log);
      log("Waiting for the R1 with USB debugging (up to 90 s). If it asks to allow this computer, tap Allow.");
      const adb = await openAdb(await waitForUsb("adb", signal));
      setMode("adb");
      return loggedAdb(adb, log);
    },
    sleep: (ms) => (dry ? Promise.resolve() : new Promise((r) => setTimeout(r, ms))),
    save(img) {
      if (dry) return log(`Would save ${img.name} to your downloads.`);
      save(img);
      log(`Saved ${img.name} to your downloads.`, "ok");
    },
    progress(label) {
      bar.hidden = false;
      return (f) => {
        bar.style.setProperty("--p", String(Math.min(1, f)));
        bar.dataset.label = `${label} · ${Math.round(f * 100)}%`;
      };
    },
  };
}

let stop: AbortController | undefined;

async function run(panel: HTMLElement, job: (ctx: Ctx, signal: AbortSignal) => Promise<unknown>, unlocked = true) {
  if (busy) return;
  busy = true;
  stop = new AbortController();
  document.body.classList.add("busy");
  $("[data-progress]", panel).hidden = true;
  const ctx = makeCtx(panel, stop.signal, unlocked);
  try {
    await job(ctx, stop.signal);
    ctx.log(dry ? "Dry run finished. Nothing was sent to the R1." : "Step finished.", "ok");
  } catch (e) {
    const err = e as Error;
    ctx.log(e instanceof Cancelled ? "Cancelled." : `Stopped: ${err.name === "AbortError" ? "you stopped waiting." : err.message}`, "err");
  } finally {
    busy = false;
    document.body.classList.remove("busy");
    void refreshMode();
  }
}

async function verified(ctx: Ctx, name: string): Promise<Img> {
  ctx.log(`Downloading ${name} and checking its sha256 against manifest.json`);
  const img = await download(name);
  ctx.log(`${name}: sha256 matches`, "ok");
  return img;
}

const JOBS: Record<string, (ctx: Ctx, signal: AbortSignal) => Promise<unknown>> = {
  async enter(ctx, signal) {
    if (dry) {
      ctx.log("Would open the MT65xx Preloader port (0E8D:2000) at 115200, wait for READY and send FASTBOOT.");
      return;
    }
    if (!serial) throw new Error("This browser has no Web Serial. Use Chrome or Edge on a computer, or Rabbit's own tool.");
    await enterFastboot((s) => ctx.log(s), signal);
  },
  ability: (ctx) => unlockAbility(ctx),
  unlock: (ctx) => unlock(ctx),
  async flash(ctx) {
    const zip = $<HTMLInputElement>("#stock").files?.[0];
    const gz = $<HTMLInputElement>("#gsi").files?.[0];
    if (!zip || !gz) throw new Error("Choose both files first: the rabbitOS zip and the LineageOS .img.gz.");
    if (!/arm64_bvN/.test(gz.name)) throw new Error(`${gz.name} isn't the arm64_bvN build (not bgN, a64 or vndklite).`);
    ctx.log(`Reading vbmeta, vbmeta_system, vbmeta_vendor and userdata from ${zip.name}`);
    const stock = await stockImages(zip);
    try {
      let gsi: Img = { name: gz.name, blob: gz };
      if (gz.name.endsWith(".gz")) {
        ctx.log(`Decompressing ${gz.name} in the browser (about 2.5 GB, written to this site's private storage on disk)`);
        gsi = await gunzip(gz, gz.name, ctx.progress("Decompressing"));
        ctx.log(`${gsi.name}: ${gsi.blob.size} bytes`);
      }
      await flashGsi(ctx, stock, gsi);
    } finally {
      await dropScratch();
    }
  },
  async root(ctx) {
    await root(ctx, await verified(ctx, "Magisk-v30.7.apk"));
  },
  async provision(ctx) {
    const [magisk, app, module] = [await verified(ctx, "Magisk-v30.7.apk"), await verified(ctx, "herdr-os.apk"), await verified(ctx, "herdr-os-module.zip")];
    const density = Number($<HTMLInputElement>("#density").value) || 0;
    await provision(ctx, { magisk, app, module }, { debloat: $<HTMLInputElement>("#debloat").checked ? DEBLOAT : null, density });
  },
};

function go(step: number) {
  for (const p of $$("[data-step]")) p.hidden = p.dataset.step !== String(step);
  for (const b of $$("[data-goto]")) b.setAttribute("aria-current", String(b.dataset.goto === String(step)));
}

export function start() {
  if (!usb) {
    $("[data-unsupported]").hidden = false;
    $<HTMLInputElement>("#dry").disabled = true;
  }
  $<HTMLInputElement>("#dry").checked = dry;
  $("#dry").addEventListener("change", (e) => {
    dry = (e.target as HTMLInputElement).checked;
    document.body.classList.toggle("dry", dry);
  });
  document.body.classList.toggle("dry", dry);

  for (const b of $$("[data-goto]")) b.addEventListener("click", () => go(Number(b.dataset.goto)));
  for (const b of $$<HTMLButtonElement>("[data-run]")) {
    const panel = b.closest<HTMLElement>("[data-step]")!;
    // Step 1's dry run starts from a locked R1, so the unlock path shows in full.
    b.addEventListener("click", () => run(panel, JOBS[b.dataset.run!], panel.dataset.step !== "1"));
  }
  $("[data-stop]").addEventListener("click", () => stop?.abort());
  $("[data-choose]").addEventListener("click", async () => {
    await usb?.requestDevice({ filters: [FASTBOOT_FILTER, ADB_FILTER] }).catch(() => {});
    void refreshMode();
  });

  usb?.addEventListener("connect", refreshMode);
  usb?.addEventListener("disconnect", refreshMode);
  serial?.addEventListener("connect", refreshMode);
  serial?.addEventListener("disconnect", refreshMode);
  go(1);
  void refreshMode();
}
