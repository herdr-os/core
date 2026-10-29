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
