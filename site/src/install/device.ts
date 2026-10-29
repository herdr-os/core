// The two device protocols as the steps see them, the log every command goes through, and the
// dry-run stand-ins that answer like an R1 without touching USB.
import type { Img, Progress } from "./files.ts";

export interface Fastboot {
  getvar(name: string): Promise<string>;
  /** A fastboot command in CLI words, e.g. "erase userdata" or "flashing unlock". Returns its text. */
  run(cli: string): Promise<string>;
  flash(partition: string, img: Img, onProgress?: Progress): Promise<void>;
  /** Sends the reboot; the caller waits for the device to come back. */
  reboot(target?: "bootloader" | "fastboot"): Promise<void>;
}

export interface ShellResult { out: string; code: number }

export interface Adb {
  shell(cmd: string): Promise<ShellResult>;
  push(file: Img, remote: string, onProgress?: Progress): Promise<void>;
  pull(remote: string): Promise<Blob>;
  /** `adb root`. True when adbd restarts, so the caller reconnects. */
  root(): Promise<boolean>;
  reboot(target?: "bootloader"): Promise<void>;
}

export interface Ctx {
  log(line: string, kind?: "cmd" | "warn" | "ok"): void;
  /** Resolves only if the person explicitly agrees; otherwise throws Cancelled. */
  confirm(title: string, erases: string): Promise<void>;
  /** Waits for a fastboot device (bootloader or fastbootd), as lib.sh's wait_fastboot does. */
  fastboot(): Promise<Fastboot>;
  /** Waits for an adb device, as `adb wait-for-device` does. */
  adb(): Promise<Adb>;
  sleep(ms: number): Promise<void>;
  save(img: Img): void;
  progress(label: string): Progress;
}

export class Cancelled extends Error {
  constructor() { super("Cancelled."); }
}

const q = (s: string) => (/[\s'"$;&|()<>*]/.test(s) ? `'${s.replace(/'/g, `'\\''`)}'` : s);

/** Echoes each command the way you'd type it in a terminal, then runs it. */
export function loggedFastboot(fb: Fastboot, log: Ctx["log"]): Fastboot {
  return {
    async getvar(name) {
      log(`fastboot getvar ${name}`, "cmd");
      const v = await fb.getvar(name);
      log(`${name}: ${v}`);
      return v;
    },
    async run(cli) {
      log(`fastboot ${cli}`, "cmd");
      const out = await fb.run(cli);
      if (out.trim()) log(out.trim());
      return out;
    },
    async flash(partition, img, onProgress) {
      log(`fastboot flash ${partition} ${img.name}`, "cmd");
      await fb.flash(partition, img, onProgress);
    },
    async reboot(target) {
      log(`fastboot reboot${target ? ` ${target}` : ""}`, "cmd");
      await fb.reboot(target);
    },
  };
}

export function loggedAdb(adb: Adb, log: Ctx["log"]): Adb {
  return {
    async shell(cmd) {
      log(`adb shell ${q(cmd)}`, "cmd");
      const r = await adb.shell(cmd);
      if (r.out.trim()) log(r.out.trim());
      return r;
    },
    async push(file, remote, onProgress) {
      log(`adb push ${file.name} ${remote}`, "cmd");
      await adb.push(file, remote, onProgress);
    },
    async pull(remote) {
      log(`adb pull ${remote}`, "cmd");
      return adb.pull(remote);
    },
    async root() {
      log("adb root", "cmd");
      return adb.root();
    },
    async reboot(target) {
      log(`adb reboot${target ? ` ${target}` : ""}`, "cmd");
      await adb.reboot(target);
    },
  };
}

/** Dry run: an R1 on slot a (unlocked unless told otherwise) that answers every command and changes nothing. */
export function dryFastboot(unlocked = true): Fastboot {
  let userspace = false;
  const vars: Record<string, () => string> = {
    "is-userspace": () => (userspace ? "yes" : "no"),
    unlocked: () => (unlocked ? "yes" : "no"),
    "current-slot": () => "a",
  };
  return {
    getvar: async (name) => vars[name]?.() ?? "",
    run: async (cli) => {
      if (cli === "flashing unlock") unlocked = true;
      return cli === "flashing get_unlock_ability" ? "get_unlock_ability: 1" : "";
    },
    flash: async (_p, _i, onProgress) => onProgress?.(1),
    reboot: async (target) => { userspace = target === "fastboot"; },
  };
}

export function dryAdb(installed: string[] = []): Adb {
  const answers: [RegExp, ShellResult][] = [
    [/^id -u$/, { out: "0", code: 0 }],
    [/^id$/, { out: "uid=0(root) gid=0(root)", code: 0 }],
    [/^getprop ro\.boot\.slot_suffix$/, { out: "_a", code: 0 }],
    [/^pm list packages -U /, { out: "package:dev.r1ptt uid:10123", code: 0 }],
    [/^pm list packages$/, { out: installed.map((p) => `package:${p}`).join("\n"), code: 0 }],
    [/^test -x \/data\/adb\/magisk\/busybox/, { out: "", code: 1 }],
    [/^pm install /, { out: "Success", code: 0 }],
  ];
  return {
    shell: async (cmd) => answers.find(([re]) => re.test(cmd))?.[1] ?? { out: "", code: 0 },
    push: async (_f, _r, onProgress) => onProgress?.(1),
    pull: async (remote) => new Blob([`dry run: ${remote}`]),
    root: async () => false,
    reboot: async () => {},
  };
}
