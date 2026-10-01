# Limoo UI style guide

**Read this before changing any UI.** The governing document is `NOTHING-UI-SPEC.md`; this file records how
that spec is realised in this codebase. Where the two ever disagree, the spec wins.

Code: `ui/Tokens.kt` (palette, spacing, radii, type) and `ui/Nothing.kt` (components, micrographics).

---

## 0. The rule that matters most

**Do not reduce this style to the dot font.** A build that only swaps in a dot typeface is a weak imitation
and fails the spec's own test: remove the dots and the design should still be distinctive.

The identity is carried by, in this order of importance:

1. Grid and spacing discipline
2. Monochrome surfaces with hairline borders
3. Typography hierarchy (size and position, not weight or colour)
4. Restrained geometry — rectangles, rings, lines, small radii
5. Hardware-inspired micrographics
6. Dot/LED graphics — **selective**, as instrumentation
7. Tactile motion
8. Sparse signal colour

### The Nothing test

Before calling any screen done:

| Test | Pass if |
|---|---|
| Remove the dot font | still feels designed |
| Remove the accent colour | hierarchy still works |
| Remove all shadows (there are none) | structure still reads |
| Remove half the cards | screen gets cleaner, not emptier |
| Squint | one obvious hierarchy |
| Inspect one component | looks like the system, not a UI kit |

## 1. Tokens

**Never hardcode a colour, radius or spacing value in a screen.** Use `Tokens.kt`.

```kotlin
val n = LocalN.current
n.bg n.surface n.surface2 n.line n.lineStrong n.text n.dim n.muted n.accent
Space.micro compact small standard card section major hero editorial
Radius.none control card raised pill
NType.display title body bodySmall label micro mono
```

### Colour (dark; light mirrors the relationships)

| Role | Value | Use |
|---|---|---|
| `bg` | `#0A0A0A` | canvas — OLED black, never dark grey |
| `surface` | `#111111` | cards, sheets |
| `surface2` | `#171717` | inner blocks, switch track |
| `line` | `#252525` | hairlines |
| `lineStrong` | `#373737` | emphasis, ticks |
| `text` | `#F2F2F2` | primary |
| `dim` | `#A0A0A0` | secondary |
| `muted` | `#6B6B6B` | metadata |
| `accent` | `#D71920` | **signal only** |

### Accent discipline

Red is a **signal**, not a theme. Use it for: live state, expiry/limits, errors, destructive actions.
Never for: ordinary buttons, selected states, labels, values.

Selected/active state is **black/white inversion** (`NButton(primary = true)`, selected `NChip`,
`NSwitch` on, `NRadio` dot) — not colour.

Accent choices: `mono` (default — no signal colour at all), `red`, `lime`, `blue`.

### Radii

`0` technical containers · `4` controls · `8` cards · `12` raised surfaces · `pill` dots and circles only.

Never a large radius on a content container. Preflight check 10 fails the build above 16dp.

### Spacing

One base unit, 4dp. `Space.*` only. Larger values are for **deliberate empty zones** — a screen with no
whitespace is not this style.

## 2. Typography

Body and headings: **platform sans**, sentence case, never uppercase. Fully readable.

`NType.micro` (uppercase, tracked, monospace) is reserved for metadata, categories and compact control
labels. Uppercase is not a default.

Hierarchy by **size → position → weight → inversion → opacity → accent**. Not by colour.

**Dot type is an instrument, not a voice.** `DotReadout` is only for live/technical values: speed, session
totals, uptime. Never for headings, navigation, buttons, form fields, body copy, or empty-state prose.

## 3. Micrographics

Instrumentation, not decoration. Every one carries information:

`DotMeter` latency (5 segments) · `SegmentedBar` capacity (turns signal past 90%) · `SignalLoader` loading
(a filling/wiping segment train — not a spinner) · `SignalDot` status · `Ticks` calibration rules ·
`DotField` empty state · `MenuMark` overflow.

## 4. Components

`NCard` `NButton` `NChip` `NSwitch` `NCheck` `NRadio` `NRow` `NField` `NSearch` `NSheet` `SheetRow`
`ChoiceRow` `ToggleRow` `FieldRow` `NumberRow` `NLabel` `NRule` `NDivider` `NStat` `SubAllowance`
`BusyRow` `BusyBlock` `MenuMark` `DotReadout` `DotMeter` `SegmentedBar` `SignalLoader` `SignalDot` `Ticks`
`DotField`

Add new UI here, not inline in a screen, so the vocabulary stays closed.

### Icons

No icon font. Two kinds only:

- **Dot-grid vectors** in `res/drawable` — `ic_launcher` (dot "L"), `ic_stat` (its status-bar reduction),
  `ic_tile` (dot ring). Built on a square grid with circular, uniformly spaced dots.
- **Drawn marks** — `MenuMark` for overflow.

Not every icon may be dots. `MenuMark` is deliberately hairlines.

### Navigation

A hairline-ruled bar with three left-aligned labels and a filled square for the active tab
(`Root.kt: NavBar`). **Not** a floating pill — the spec names giant floating nav pills as an anti-pattern.

### Cards

Cards are not the default container. Use them when content genuinely needs grouping. Prefer `NRow` +
`NDivider` for lists — a list of hairline-separated rows beats a stack of cards.

## 5. Motion

120–180ms micro · 180–280ms normal · 280–450ms large. Cubic easing, `tween`. **No spring, no bounce, no
infinite animation** except the loader and the connecting ring.

Haptics on every tap via `rememberTick()`; `LongPress` for toggles. Respect `LocalHaptics`.

Any action over ~300ms shows progress: imports and subscription fetches set `Actions.setBusy`, which
renders `BusyRow` and disables the confirm button.

## 6. Gotchas that have bitten us

- **Never post a RemoteViews (custom-layout) notification.** SystemUI inflates notifications in its own
  process and rejected our layout twice — first an app-defined View, then plain platform widgets — both
  times as `RemoteServiceException$BadForegroundServiceNotificationException: Bad notification(tag=null,
  id=1)`, which kills the app because the notification is built inside `startForeground()`. The
  notification now uses platform templates only. Preflight check 6 forbids `RemoteViews` and any
  `res/layout` notification layout from returning.
- `startForeground()` must never throw — it is on the connect path. `minimalForeground()` is the fallback.
- The Xray core's `initCoreEnv` base key must be 32 bytes as **unpadded base64url** (43 chars), not hex.
- Traffic-stat keys are **`uplink` / `downlink`**, not `up` / `down`. Wrong keys fail silently.
- The delay probe needs `initCoreEnv` to have run and must use `XrayConfigBuilder.buildProbe` (no inbounds —
  a probe that binds the SOCKS port collides with the running core).
- Ring geometry must derive from **one pitch**, centred; two independent radii produce a ring that looks
  lopsided. Verify by mirroring the point set about both axes.
- **Never call a Compose extension function fully qualified** (`androidx.compose.foundation.lazy.items(...)`
  does not resolve). Import it.
- A class may declare only **one** companion object.
- A Kotlin property already generates its `setX` accessor — do not also declare `fun setX`.

## 7. Pre-flight

Run before pushing:

```bash
python3 tools/preflight.py
```

Checks: `android.*` imports resolve against `android.jar` · SDK methods exist · positional-arg counts ·
JVM accessor clashes · no RemoteViews · RemoteViews-safe layouts · brace balance · one companion object ·
unterminated char literals · no removed-component references · radius cap.
