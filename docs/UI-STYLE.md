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

- **Dot-grid vectors** in `res/drawable` — `ic_launcher` (the traced Limoo "L" mark on OLED black, with a themed-icon monochrome layer), `ic_stat` (the same mark as a flat silhouette),
  `ic_tile` (dot ring). Built on a square grid with circular, uniformly spaced dots.
- **Drawn marks** — `MenuMark` for overflow.

Not every icon may be dots. `MenuMark` is deliberately hairlines.

### Widget

`widget/LimooWidget.kt` (Glance, so no hand-written RemoteViews): `widget_bg` = card surface + hairline,
`widget_ring_on/off` = dashed oval echoing the connect ring. State label in mono micro caps; accent only for error/blocked.

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
- `queryAllOutboundTrafficStats()` returns **plain text** `tag,uplink|downlink,value;` and **resets the
 counters on read**. Values are deltas; only `LimooVpnService` may poll it. Count `proxy` + `direct` only
 (`fragment` is a dialerProxy under `proxy`, counting it doubles bytes).
- Format numbers for `DotReadout` with `Locale.US`: Persian/Arabic digits have no glyph and render blank.
- The Xray config **must declare `stats` and a `policy` with `statsOutboundUplink/Downlink`** or `queryAllOutboundTrafficStats()` has no counters
  to report and every live figure silently reads zero. See `XrayConfigBuilder.build`.
- The delay probe needs `initCoreEnv` to have run and must use `XrayConfigBuilder.buildProbe` (no inbounds —
  a probe that binds the SOCKS port collides with the running core).
- Ring geometry must derive from **one pitch**, centred; two independent radii produce a ring that looks
  lopsided. Verify by mirroring the point set about both axes.
- **Never call a Compose extension function fully qualified** (`androidx.compose.foundation.lazy.items(...)`
  does not resolve). Import it.
- A class may declare only **one** companion object.
- A Kotlin property already generates its `setX` accessor — do not also declare `fun setX`.
- **The GLYPHS table is built with `associate()`, which keeps the LAST entry for a duplicate key.** A
  typo'd duplicate line therefore renders the wrong glyph with no compile error — a stray `/ 0 0 0 0 0 0 0`
  shipped a blank row where the "/" belonged, and it read as a garbled character after the speed figures.
  Preflight check 12 fails on any duplicate key. Never add a glyph without checking the key is unused.
- Notification text: the template's `setContentText` is one cramped line. Use `setSubText` for the second
  stat rather than packing both into one string.
- The status-bar icon is a flat silhouette — Android tints it and discards colour, so only the letterform
  survives. Do not try to put fine detail in it.

## 5a. Swipe actions (One UI style)

`ui/SwipeRow.kt` -> `SwipeActionRow`. Replaces `SwipeToDismissBox` entirely.

- **Right = Share, Left = Delete.** Share opens the sheet, which offers the standard `vless://` link, a
  `limoo://` link, or a `.limoo` file. Favourite is no longer a swipe action; it stays in the row menu and
  the multi-select bar.
- **The row tracks the finger exactly, 1:1, at one constant speed** for its whole travel. An earlier
  version damped the drag past a threshold to feel "heavy"; it read as laggy instead, because the row
  fighting the touch desyncs it from the finger. Do not add damping, rubber-banding or per-zone speeds.
- **Reveal, don't fire.** Past **75%** the row latches open: it completes its travel and the action button
  appears at full size. Releasing does NOT run the action - the button has to be tapped. A gesture that
  commits on release is how servers get deleted by accident.
- **The button fills the revealed area** and the full row height. An earlier version used a small pill
  detached from the row edge, which read as a floating control rather than part of the list.
- **Animation only on release.** While a finger is down the row is driven straight from the drag value;
  the spring runs only when settling. Animating during the drag is what produced the jank.
- Speed is deliberately unhurried: the travel is 75% of the row, so a normal swipe takes a deliberate
  moment rather than a flick.
- **Never destructive on the gesture itself.** The row stays in place and the caller acts; delete still
  goes through `Ui.say(..., "UNDO")`. A mis-swipe must always be recoverable.
- Delete uses the signal colour - the one place a swipe background takes the accent.

## 5b. Accent-reactive wallpaper

`ui/Wallpaper.kt`. Two layers behind every tab: the monochrome `limoo_wallpaper_base`, then
`limoo_accent_mask` tinted with the current accent.

- **Do not resample, palette-quantise or re-encode the assets.** They live in `res/drawable-nodpi/` at
  their authored 852x1846; the base is 1.9 MB and that is accepted on purpose.
- **The mask's alpha is the mechanism**, not an optimisation: white, partial alpha (max ~190), zero fully
  opaque pixels. `ColorFilter.tint` recolours the RGB and preserves alpha, so only the masked details take
  the accent. Flattening or compositing the mask would tint the whole frame. Preflight 14b enforces this.
- **The accent comes from `LocalN`**, derived from the stored `AppSettings.accent`. Never add a second
  accent source or a dedicated preference - that is what makes the wallpaper retint live.
- **The accent cross-fades** over 320ms (`animateColorAsState`) rather than snapping, so both the UI and
  the wallpaper tint ease to the new colour. Keep it short: long fades read as lag.
- Applied on **every tab**, scrim raised where dense rows sit over artwork: 0.10 Home, 0.45 Servers and
  Settings. Cards carry their own surface fill, so text stays legible. Both layers use `ContentScale.Crop`;
  never `FillBounds`, which would stretch the artwork.

## 6b. Release signing

Without a keystore, CI signs the release APK with a **throwaway debug key regenerated every run**. Android
then treats each build as a different app and refuses to install over the previous one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so the user has to uninstall first - which loses all data.

Fix once with `tools/make-keystore.sh`, which prints the exact `gh secret set` commands. Afterwards every
build upgrades cleanly. The workflow emits a warning annotation whenever it has to fall back to the debug
key, so the cause is never a mystery.

Never commit a keystore or its base64. Losing the key means existing installs can never be updated again
without another uninstall.

## 7. Pre-flight

Run before pushing:

```bash
python3 tools/preflight.py
```

Checks: `android.*` imports resolve against `android.jar` · SDK methods exist · positional-arg counts ·
JVM accessor clashes · no RemoteViews · RemoteViews-safe layouts · brace balance · one companion object ·
unterminated char literals · no removed-component references · radius cap.
