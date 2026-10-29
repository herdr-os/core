# Herdr OS design

The R1 is a 480×640 panel with a wheel and one side button. Herdr OS shows the herd as sheep on a
pasture: one sheep per agent, the one the wheel has picked standing in front. The full design,
with every journey, is the Paper page "Herdr OS · vision"; `design-reference/preview/` holds
480×640 exports of key screens, and `tools/design-preview.sh` shows them on a real R1.

## Rules

1. **Two layers.** The companion layer (pasture, sheep asking) is the front door. Detail screens
   (terminal, changes, files, new task) are the workbench underneath.
2. **One sheep per agent.** Its wool and the logo on its back are its harness's brand. Pose hints
   at state (asleep when idle); words always say it.
3. **Orange is you.** Rabbit orange marks your voice, your attention and anything waiting on you:
   the needs-you badge, the answer you are about to send. Plain next steps are cream.
4. **Wheel moves, button talks, touch approves.** The side button never answers a question.
5. **Readable at arm's length.** The R1 is 2.9″ at about 278 ppi. No text under 13 design px, no
   grey dimmer than `dim`, touch targets of at least 56 px.

## Tokens

robotOS's tokens stay (`res/values/colors.xml`, `ui/DeviceUi.kt`): black canvas, warm white type,
`accent` orange, rose `error`, green `ok`. Herdr adds:

| Token | Use |
|---|---|
| `herd_claude` / `herd_claude_ink` | Claude Code's clay and the type that reads on it |
| `herd_codex` / `herd_codex_ink` | Codex's white and black |
| `herd_pi` / `herd_pi_ink` | Pi's blue and navy |
| `herd_other` / `herd_other_ink` | Any other harness, with the plain sheep |
| `cream` | The companion's voice and plain next-step buttons |
| `field_day`, `field_line` | The pasture's ground and grid |

`herdr/ui/Harness.kt` is the one table from an agent's kind to these colors and its sheep.
`herdr/ui/Design.kt` sizes everything in Paper's 480×640 design pixels, scaled to the screen, with
the type scale 44 / 28 / 22 for headings, 18 / 16 for body and 14 / 13 for labels.

## Screens

| Screen | Holds |
|---|---|
| Pasture (home) | Status line, the companion's headline, the field with up to six sheep, the front sheep's question in its own color, wheel and tap hints. Past six agents, one flock per host; tapping a flock opens that host as its own field (past six there, the picked sheep and the next five, moving with the wheel), and Back returns to the flocks. Unpaired, connecting and empty herds show the companion alone with one next step |
| Sheep asks (decision) | The agent's sheep with its question in a bubble of its color, options as pills, the one you would send in orange, then a confirmation naming the destination |
| Agent | The sheep beside its name, project, state, host, and its model, effort and context use; a rose line when it hit a usage limit; an orange answer button when it is waiting; Message, Terminal, Changes, Files, Stop; its subagents and background commands (running first, then the last three that ended); recent activity |
| Asleep | Over the lock screen after the screen turns off: clock, date, battery, the sleeping companion, counts only (never names or questions), hosts, "hold to wake". A long press wakes to the briefing if a card newly needs you, failed or finished |
| Briefing | The companion's summary of the herd in a cream bubble with the harness logos it mentions, "Show me" (orange only when that agent waits on you) and "Later". Spoken when Speak alerts is on and a voice is set up, silent otherwise |
| Inbox | Agents waiting on you on top, then earlier outcomes from `/v1/activity` with harness logo chips: done, failed, answered, expired, sent, refused |
| The Herd | Every agent as a deck of harness-colored cards, filterable by host; the wheel deals the next card |
| Quick controls | Clock, battery, Wi-Fi, Tailscale or hosts, Speak alerts, Quiet, power mode (Desk is automatic on power), brightness, volume, Settings, sleep |
| Set up | Welcome, network (Wi-Fi, Tailscale, pair), scan, confirm what the bridge grants and when the code expires, then practise the side button |
| Thread | The conversation from the agent's session file: you in orange, the agent in its brand color, photos inline. Hold to talk sends on release |
| Walkie | One agent per channel, the wheel changes channel; hold talks (orange), its reply fills the screen in its brand color and is spoken when voice is set up. A spoken "yes" never answers a question |
| Show it | The R1 camera; the photo is saved on the agent's machine and the agent is sent its path |
| Subagents, Background tasks | The lead and each subagent (latest step, model, context) or each background command (elapsed, exit code, last output lines) |
| Terminal | Sanitized live output; Type switches to sending keys (esc, tab, ^C, arrows, enter) |
| Model | The harness's models with the one in use and effort; picking sends the harness's own `/model` and `/effort` (Codex opens its own menu) |
| New task | Your words, the agent that takes it with its host, project and model, then "Send to the herd" |
| Settings, Hosts, Voice & companion, Update | Rows for hosts, voice, power and updates; hosts with status, local or SSH, and Herdr version |
| When things go sideways | Boot, can't reach the herd, low battery, answer not confirmed, no sheep, inbox zero, host down, crashed, didn't catch that, already answered, rate limited (wait, or finish on Sonnet), context full (compact) |
| Shade | Herdr OS's own pull-down, on every Herdr screen except asleep and setup: drag down from the top band. Clock and battery, the herd's status with what needs you in orange, the waiting question with Answer, Inbox / Walkie / New task / Briefing, Wi-Fi, Tailscale, Speak alerts, Quiet, brightness. Opens past halfway; the wheel moves focus, a tap acts, a drag up or Back closes. Android's own shade is collapsed if an edge swipe opens it |
| Changes, Files | Unchanged from v1 |

## Sheep

`design-reference/sheep/` holds the source SVGs: the plain Herdr sheep (the companion) and one
branded sheep per harness, awake and asleep. `tools/render-sheep.sh` renders them into
`res/drawable-nodpi/`; the logo sits on a skewed face that Android vector drawables can't express,
so they ship as PNGs. Logos and their sources are in `design-reference/harness-logos/`.

Every harness Herdr runs is one row in `design-reference/harnesses.json`: its Herdr kinds, wool
color, ink and logo. `tools/make-herd.py` builds the rest from it: the branded sheep (Claude Code,
Codex, Pi and OpenCode were drawn by hand and are kept), `Harness.kt`, the app's colors and the
website's carousel. A harness with no published mark carries a lettermark. Add a harness by adding a
row, then run `tools/make-herd.py` and `tools/render-sheep.sh`. An agent kind not in the table
grazes as a plain sheep.

## The OS around the app

Stock Android should not show through. The Magisk module (`magisk/r1ptt-system`) turns off Android's
keyguard so the asleep screen is the only lock, and installs the boot animation that
`tools/render-boot.py` builds from the Paper "Run · Boot" screen. Herdr screens hide Android's
status and navigation bars, use the app's own toasts, volume overlay and dialog theme, and the shade
replaces the notification shade. Still stock: the keyboard and Android's Wi-Fi picker.

## Motion

All of it lives in `herdr/ui/Motion.kt`, so every screen moves the same way. Durations are written
for the R1's 0.5 animator scale; Android's "Remove animations" and haptics settings still apply.

- **Wheel:** every detent a screen uses ticks under the thumb. On the pasture the herd walks to its
  new places (each sheep springs from where it stood); a sheep new to the field pops in.
- **Touch:** pills, tiles, shortcuts, toggles and menu rows sink a little and spring back, with a
  click. A sent answer gives a firmer confirmation.
- **Screens:** a new screen rises in while the one underneath settles back; Back reverses it. A sheep
  you tap walks forward as the field dims under a single veil.
- **Arrivals:** menu and inbox rows rise in once when the screen opens; new chat bubbles slide in
  from their speaker's side. The shade follows the finger and opens past halfway.
- **Never idle:** nothing animates while nothing changes. Endless animations run only while their
  view is on screen (an orphaned one cost 56 callbacks a second, unseen).
- **State:** a new question makes its sheep hop once; a sheep that finishes dozes off; stale data
  dims and reads "Last known". Sheep colors and orange never change with the sky.

Agent, Message and Decision are pages (`herdr/ui/Page.kt`) stacked on the pasture in its own
window, not activities: opening one swaps a view instead of creating a window, which was most of the
remaining jank. Their old activity names start the pasture with the page on top, so links and
notifications still land; Back from such a page returns to the screen that opened it.

`tools/r1-perf.sh [runs] [--warm]` measures frame times on a connected R1 over one scripted pass;
screens have named trace sections (`HerdActivity.render`, `.sheep`, `.header`…) for perfetto.
