# Herdr OS site

The showcase site for Herdr OS: Astro, GSAP (ScrollTrigger, SplitText, MotionPath) and the real
screens from the Paper file "Herdr OS · vision".

```sh
npm install
npm run dev       # http://localhost:4321
npm run build     # static site in dist/
```

`src/screens/` holds every R1 screen as live HTML, generated from Paper with `tools/paper2astro.mjs` (`paper-src/` keeps each artboard's JSX; `/screens` shows them all). `public/sheep/` holds the sheep and
harness logos from `design-reference/`. Re-export from Paper when the designs change. All motion
lives in `src/scripts/motion.ts` and is skipped for visitors who ask for reduced motion.

## Install page and downloads

`/install` is a Chrome installer that takes a stock R1 to Herdr OS over USB (WebUSB for adb and
fastboot, Web Serial for the preloader). It ports `tools/fastboot-entry.py`, `flash-gsi.sh`,
`root.sh` and `provision.sh` command for command, confirms before anything that erases, and has a
dry run. It needs HTTPS. Its logic is in `src/install/`; `node --test src/install/install.test.ts`
checks the pure parts against the real scripts and files.

`public/downloads/` holds the app, the Magisk module, Magisk and the bridge with `manifest.json`
and `SHA256SUMS`; rebuild them with `tools/package-downloads.sh` from the core repo. The installer
verifies each file's SHA-256 before using it.
