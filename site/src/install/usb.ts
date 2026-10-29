// The real drivers: fastboot through android-fastboot, adb through @yume-chan/adb, both on WebUSB.
import { FastbootDevice } from "android-fastboot/dist/fastboot.mjs";
import { Adb as YumeAdb, AdbDaemonTransport } from "@yume-chan/adb";
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from "@yume-chan/adb-daemon-webusb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";
import type { Adb, Fastboot } from "./device.ts";
import type { Img } from "./files.ts";

export const FASTBOOT_FILTER = { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x03 };
export const ADB_FILTER = { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x01 };
export type UsbMode = "fastboot" | "adb";

export function usbMode(d: USBDevice): UsbMode | null {
  for (const c of d.configurations)
    for (const i of c.interfaces)
      for (const a of i.alternates) {
        if (a.interfaceClass !== 0xff || a.interfaceSubclass !== 0x42) continue;
        if (a.interfaceProtocol === 0x03) return "fastboot";
        if (a.interfaceProtocol === 0x01) return "adb";
      }
  return null;
}

export const BUSY_HINT = "Another program holds the R1. Quit adb on this computer (run `adb kill-server`) and close any other flashing tool, then try again.";

/** Polls the devices this page may use, as wait_fastboot / adb wait-for-device do. */
export async function waitForUsb(mode: UsbMode, signal: AbortSignal, seconds = 90): Promise<USBDevice> {
  for (let i = 0; i < seconds * 2; i++) {
    signal.throwIfAborted();
    const d = (await navigator.usb.getDevices()).find((x) => usbMode(x) === mode);
    if (d) return d;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No ${mode} device after ${seconds} s. If the R1 is in ${mode} mode, press “Choose the R1” so Chrome lets this page use it.`);
}

const SPARSE_MAGIC = 0xed26ff3a;
const CHUNK = 64 * 1024 * 1024;

/**
 * Wraps a big raw image as a sparse image of RAW chunks that point at slices of the same Blob.
 * android-fastboot would otherwise read a raw image larger than max-download-size into one
 * ArrayBuffer (the 2.5 GB GSI); this way it reads one split at a time.
 */
export async function asSparse(blob: Blob): Promise<Blob> {
  const head = new DataView(await blob.slice(0, 4).arrayBuffer());
  if (blob.size <= CHUNK || (head.byteLength === 4 && head.getUint32(0, true) === SPARSE_MAGIC)) return blob;
  const pad = (4096 - (blob.size % 4096)) % 4096;
  const raw = pad ? new Blob([blob, new Uint8Array(pad)]) : blob;
  const parts: BlobPart[] = [];
  let chunks = 0;
  for (let off = 0; off < raw.size; off += CHUNK, chunks++) {
    const len = Math.min(CHUNK, raw.size - off);
    const h = new DataView(new ArrayBuffer(12));
    h.setUint16(0, 0xcac1, true); // CHUNK_TYPE_RAW
    h.setUint32(4, len / 4096, true);
    h.setUint32(8, 12 + len, true);
    parts.push(h.buffer, raw.slice(off, off + len));
  }
  const f = new DataView(new ArrayBuffer(28));
  f.setUint32(0, SPARSE_MAGIC, true);
  f.setUint16(4, 1, true);
  f.setUint16(8, 28, true);
  f.setUint16(10, 12, true);
  f.setUint32(12, 4096, true);
  f.setUint32(16, raw.size / 4096, true);
  f.setUint32(20, chunks, true);
  return new Blob([f.buffer, ...parts]);
}

/** AOSP fastboot sends "erase:userdata"; `flashing` subcommands keep their space. */
export const toRaw = (cli: string) => (cli.startsWith("flashing ") ? cli : cli.replace(" ", ":"));

const fastboots = new WeakMap<USBDevice, Fastboot>();

export async function openFastboot(dev: USBDevice): Promise<Fastboot> {
  const known = fastboots.get(dev);
  if (known && dev.opened) return known;
  const fb = new FastbootDevice();
  // The library re-grabs any USB device that connects; this page waits for the right one itself.
  fb._registeredUsbListeners = true;
  fb.device = dev;
  try {
    await fb._validateAndConnectDevice();
  } catch (e) {
    throw new Error(`${(e as Error).message}. ${BUSY_HINT}`);
  }
  const driver: Fastboot = {
    getvar: async (name) => (await fb.getVariable(name)) ?? "",
    run: async (cli) => (await fb.runCommand(toRaw(cli))).text,
    flash: async (partition, img, onProgress) => fb.flashBlob(partition, await asSparse(img.blob), onProgress),
    reboot: async (target) => {
      await fb.runCommand(target ? `reboot-${target}` : "reboot");
      fastboots.delete(dev);
    },
  };
  fastboots.set(dev, driver);
  return driver;
}

const credentials = new AdbWebCredentialStore("Herdr OS installer");
const adbs = new WeakMap<USBDevice, Adb>();

export async function openAdb(dev: USBDevice): Promise<Adb> {
  const known = adbs.get(dev);
  if (known) return known;
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER!;
  const device = (await manager.getDevices()).find((d) => d.raw === dev);
  if (!device) throw new Error("This USB device has no adb interface.");
  let connection;
  try {
    connection = await device.connect();
  } catch (e) {
    if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) throw new Error(BUSY_HINT);
    throw e;
  }
  const transport = await AdbDaemonTransport.authenticate({ serial: device.serial, connection, credentialStore: credentials });
  const adb = new YumeAdb(transport);
  const forget = () => adbs.delete(dev);
  void adb.disconnected.then(forget);

  const driver: Adb = {
    async shell(cmd) {
      const sp = adb.subprocess.shellProtocol;
      if (!sp) throw new Error("This adbd has no shell protocol v2 (Android 7 or newer needed).");
      const r = await sp.spawnWaitText([cmd]);
      return { out: r.stdout + r.stderr, code: r.exitCode };
    },
    async push(img: Img, remote, onProgress) {
      let sent = 0;
      const counted = img.blob.stream().pipeThrough(new TransformStream<Uint8Array, Uint8Array>({
        transform(chunk, c) { sent += chunk.byteLength; onProgress?.(sent / img.blob.size); c.enqueue(chunk); },
      }));
      const sync = await adb.sync();
      try {
        await sync.write({ filename: remote, file: counted, permission: 0o644 });
      } finally {
        await sync.dispose();
      }
    },
    async pull(remote) {
      const sync = await adb.sync();
      try {
        const parts: Uint8Array[] = [];
        const reader = sync.read(remote).getReader();
        for (let r = await reader.read(); !r.done; r = await reader.read()) parts.push(r.value);
        return new Blob(parts);
      } finally {
        await sync.dispose();
      }
    },
    async root() {
      const out = await adb.createSocketAndWait("root:");
      if (!/restarting/i.test(out)) return false;
      forget();
      await Promise.race([adb.disconnected, new Promise((r) => setTimeout(r, 10_000))]);
      await adb.close().catch(() => {});
      return true;
    },
    async reboot(target) {
      forget();
      await adb.power.reboot(target ?? "");
    },
  };
  adbs.set(dev, driver);
  return driver;
}
