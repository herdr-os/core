import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { MotionPathPlugin } from "gsap/MotionPathPlugin";

gsap.registerPlugin(ScrollTrigger, SplitText, MotionPathPlugin);

const $ = <T extends Element = HTMLElement>(s: string, root: ParentNode = document) => root.querySelector<T>(s)!;
const $$ = <T extends Element = HTMLElement>(s: string, root: ParentNode = document) => [...root.querySelectorAll<T>(s)];

const mm = gsap.matchMedia();

// The nav gains a backdrop once the page moves, and tucks away while you read downward.
const nav = $(".nav");
ScrollTrigger.create({
  start: 80, end: "max",
  onUpdate: (self) => { nav.classList.add("solid"); nav.classList.toggle("tucked", self.direction === 1 && self.scroll() > 400); },
  onLeaveBack: () => nav.classList.remove("solid", "tucked"),
});

mm.add("(prefers-reduced-motion: no-preference)", () => {
  // Hero: the headline rises line by line, the R1 tips forward, the herd hops in.
  const lines = $$(".hero h1 .line");
  const split = lines.map((l) => SplitText.create(l, { type: "words", mask: "words" }));
  const intro = gsap.timeline({ defaults: { ease: "expo.out" } });
  intro
    .from(split.flatMap((s) => s.words), { yPercent: 110, duration: 1.1, stagger: 0.06 })
    .from("[data-hero-r1]", { rotateX: 38, rotateY: -22, y: 120, opacity: 0, duration: 1.6 }, 0.1)
    .from("[data-fade]", { y: 24, opacity: 0, duration: 1, stagger: 0.1 }, 0.5)
    .from("[data-float]", { scale: 0, opacity: 0, duration: 0.9, ease: "back.out(2.2)", stagger: 0.09 }, 0.8)
    .from(".scroll-cue", { opacity: 0, duration: 1 }, 1.2);

  $$("[data-float]").forEach((el, i) => {
    gsap.to(el, { y: i % 2 ? -14 : 14, rotate: i % 2 ? 4 : -4, duration: 2.4 + i * 0.3, ease: "sine.inOut", yoyo: true, repeat: -1, delay: 1.6 + i * 0.2 });
  });

  // The hero R1 leans toward the pointer, as if you were holding it.
  if (matchMedia("(pointer: fine)").matches) {
    const stage = $(".hero .stage");
    gsap.set(stage, { transformStyle: "preserve-3d" });
    const ry = gsap.quickTo(stage, "rotateY", { duration: 0.9, ease: "power3.out" });
    const rx = gsap.quickTo(stage, "rotateX", { duration: 0.9, ease: "power3.out" });
    window.addEventListener("pointermove", (e) => { ry((e.clientX / innerWidth - 0.5) * 16); rx((e.clientY / innerHeight - 0.5) * -12); });
  }

  gsap.to("[data-hero-r1]", {
    yPercent: 18, rotateX: -12, rotateY: 14, scale: 0.92, ease: "none",
    scrollTrigger: { trigger: ".hero", start: "top top", end: "bottom top", scrub: true },
  });

  // Every R1 wheel turns with the page, like the real one under your thumb.
  $$("[data-wheel]").forEach((w) => {
    gsap.to(w, { backgroundPositionY: "0px, 1400px, 0px", ease: "none", scrollTrigger: { trigger: document.body, start: "top top", end: "bottom bottom", scrub: 0.4 } });
  });

  // Section titles: words come up out of the dark.
  $$("[data-words]").forEach((el) => {
    const s = SplitText.create(el, { type: "words", mask: "words" });
    gsap.from(s.words, { yPercent: 100, duration: 1, ease: "expo.out", stagger: 0.04, scrollTrigger: { trigger: el, start: "top 82%" } });
  });

  gsap.from("[data-carousel] .sheep-card:nth-child(-n+6)", {
    y: 80, opacity: 0, rotate: (i) => [-4, 3, -2, 4][i % 4], duration: 1.1, ease: "expo.out", stagger: 0.08,
    scrollTrigger: { trigger: "[data-carousel]", start: "top 85%" },
  });

  // The four rules light up word by word as you read them.
  $$("[data-reveal]").forEach((el) => {
    const small = el.querySelector("small");
    small?.remove();
    const s = SplitText.create(el, { type: "words", wordsClass: "word" });
    if (small) el.append(small);
    gsap.to(s.words, { color: (i, w: HTMLElement) => (w.textContent?.match(/orange|you\./i) ? "#ff4f12" : "#f4efe6"), stagger: 0.15, ease: "none",
      scrollTrigger: { trigger: el, start: "top 78%", end: "bottom 55%", scrub: true } });
    if (small) gsap.from(small, { opacity: 0, y: 16, scrollTrigger: { trigger: el, start: "top 60%", end: "top 45%", scrub: true } });
  });

  // Journeys: the R1 stays put while the chapters turn its screen.
  const journeys = $("[data-journeys]");
  const chapters = $$<HTMLElement>("[data-chapter]", journeys);
  const screens = new Map($$<HTMLElement>("[data-screen]", journeys).map((img) => [img.dataset.screen!, img]));
  const bars = $$(".rail b", journeys);
  const steps = chapters.flatMap((c, ci) => c.dataset.screens!.split(",").map((s) => ({ ci, s })));
  let shown = steps[0].s, chapterShown = 0;
  const show = (k: number) => {
    const { ci, s } = steps[Math.min(k, steps.length - 1)];
    if (s !== shown) {
      gsap.to(screens.get(shown)!, { opacity: 0, duration: 0.35 });
      gsap.fromTo(screens.get(s)!, { opacity: 0, scale: 1.04 }, { opacity: 1, scale: 1, duration: 0.5, ease: "power2.out" });
      shown = s;
    }
    if (ci !== chapterShown) {
      // The old text clears before the new one arrives, so the two never overlap.
      gsap.to(chapters[chapterShown], { opacity: 0, y: -24, duration: 0.2, ease: "power1.in", overwrite: true });
      gsap.fromTo(chapters[ci], { opacity: 0, y: 32 }, { opacity: 1, y: 0, duration: 0.5, delay: 0.2, ease: "expo.out", overwrite: true });
      chapterShown = ci;
    }
  };
  ScrollTrigger.create({
    trigger: journeys, start: "top top", end: () => `+=${steps.length * window.innerHeight * 0.55}`, pin: ".journeys .pin", scrub: true,
    snap: { snapTo: 1 / (steps.length - 1), duration: 0.4, ease: "power1.inOut" },
    onUpdate: (self) => {
      const k = Math.round(self.progress * (steps.length - 1));
      show(k);
      // Each bar fills across its own chapter's screens.
      const at = self.progress * (steps.length - 1);
      bars.forEach((b, i) => {
        const first = steps.findIndex((x) => x.ci === i), last = steps.findLastIndex((x) => x.ci === i);
        gsap.set(b, { scaleX: gsap.utils.clamp(0, 1, (at - first + 1) / (last - first + 1)) });
      });
    },
  });

  // Many hosts: the two screens drift apart in depth.
  gsap.fromTo("[data-shots] .shot:first-child", { y: 80, rotate: -6 }, { y: -40, rotate: -2, ease: "none", scrollTrigger: { trigger: "[data-shots]", scrub: true } });
  gsap.fromTo("[data-shots] .shot:last-child", { y: 160, rotate: 7 }, { y: -80, rotate: 3, ease: "none", scrollTrigger: { trigger: "[data-shots]", scrub: true } });

  gsap.utils.toArray<HTMLElement>("[data-rise]").forEach((el, i) => {
    gsap.from(el, { y: 70, opacity: 0, duration: 1, ease: "expo.out", delay: (i % 4) * 0.08, scrollTrigger: { trigger: el, start: "top 88%" } });
  });

  // How it works: the wires draw themselves, then packets run along them.
  const wires = $$<SVGPathElement>(".diagram .wire");
  wires.forEach((p) => { const len = p.getTotalLength(); gsap.set(p, { strokeDasharray: len, strokeDashoffset: len }); });
  const diagram = gsap.timeline({ scrollTrigger: { trigger: ".diagram", start: "top 75%" } });
  diagram.to(wires, { strokeDashoffset: 0, duration: 1.2, ease: "power2.inOut", stagger: 0.15 })
    .from(".diagram .node", { scale: 0.85, opacity: 0, transformOrigin: "center", duration: 0.6, ease: "back.out(2)", stagger: 0.08 }, 0);
  const pulses = $$<SVGCircleElement>("[data-pulse]");
  gsap.set(pulses, { opacity: 0 });
  diagram.call(() => pulses.forEach((c, i) => gsap.to(c, {
    opacity: 1, duration: 1.4, ease: "power1.inOut", repeat: -1, repeatDelay: 0.4 + i * 0.3,
    motionPath: { path: `#${c.dataset.pulse}`, align: `#${c.dataset.pulse}`, alignOrigin: [0.5, 0.5] },
  })));

  gsap.from("footer .big", { yPercent: 40, opacity: 0, ease: "expo.out", duration: 1.4, scrollTrigger: { trigger: "footer", start: "top 90%" } });
});

// Reduced motion: no pinning or scrubbing; the journeys read as a plain stack with their first screens.
mm.add("(prefers-reduced-motion: reduce)", () => {
  $$("[data-chapter]").forEach((c) => { (c as HTMLElement).style.gridArea = "auto"; (c as HTMLElement).style.opacity = "1"; });
});
