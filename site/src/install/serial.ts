// tools/fastboot-entry.py over Web Serial: while a powered-off R1 starts, its MediaTek preloader shows
// a serial port (0E8D:2000) for about 1.5 s and prints READY; answering FASTBOOT stops the bootloader
// in fastboot mode.

export const PRELOADER = { usbVendorId: 0x0e8d, usbProductId: 0x2000 };

export const isPreloader = (p: SerialPort) => {
  const i = p.getInfo();
  return i.usbVendorId === PRELOADER.usbVendorId && i.usbProductId === PRELOADER.usbProductId;
};

/** One try, as the script's loop body: open at 115200, read 5 bytes (2 s timeout), answer if READY. */
export async function handshake(port: SerialPort, mode = "FASTBOOT"): Promise<boolean> {
  try {
    await port.open({ baudRate: 115200 });
  } catch {
    return false; // gone already: we missed this window
  }
  try {
    const reader = port.readable!.getReader();
    const got: number[] = [];
    const timer = setTimeout(() => reader.cancel().catch(() => {}), 2000);
    try {
      while (got.length < 5) {
        const { value, done } = await reader.read();
        if (done) break;
        got.push(...value);
      }
    } finally {
      clearTimeout(timer);
      reader.releaseLock();
    }
    if (String.fromCharCode(...got.slice(0, 5)) !== "READY") return false;
    const writer = port.writable!.getWriter();
    await writer.write(new TextEncoder().encode(mode));
    writer.releaseLock();
    return true;
  } catch {
    return false;
  } finally {
    await port.close().catch(() => {});
  }
}

/**
 * Must start from a click: if Chrome doesn't know the preloader port yet, this opens its chooser.
 * After that it polls the granted ports every 50 ms for up to 10 minutes, like the script.
 */
export async function enterFastboot(log: (s: string) => void, signal: AbortSignal): Promise<void> {
  if (!(await navigator.serial.getPorts()).some(isPreloader)) {
    log("Plug in the powered-off R1 now and choose “MT65xx Preloader” in Chrome's list, quickly.");
    await navigator.serial.requestPort({ filters: [PRELOADER] });
  }
  log("Waiting for the preloader. If you missed it, unplug the R1 and plug it in again.");
  const deadline = Date.now() + 600_000;
  while (Date.now() < deadline) {
    signal.throwIfAborted();
    for (const port of (await navigator.serial.getPorts()).filter(isPreloader)) {
      if (await handshake(port)) return log("Sent FASTBOOT. The R1 should show up in fastboot mode in a few seconds.");
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error("Timed out waiting for the preloader.");
}
