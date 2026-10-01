# Limoo UI style guide

The visual language is **Nothing-inspired**: information-dense, monospaced, high-contrast, almost no
decoration. Everything is drawn from a few primitives instead of imported assets.

Read this before changing any UI in `app/src/main/java/app/limoo/ui/`. Match the existing vocabulary;
do not introduce a new colour, radius or font without extending `Nothing.kt` first.

---

## 0. Where this language comes from

Nothing's design language (NDot / NType, dot-grid iconography, Glyph Interface). The transferable ideas,
in the order they matter for this app:

1. **Dot matrix as DNA, not as a font choice.** The dot grid is the *material* the UI is made from:
   indicators, meters, bars, icons and readouts are all dots on one pitch. That is why `NDots`,
   `DotMeter`, `DotBar`, `NSpinner`, `NGlyph`, `NDotsProgress`, `DotStripView` and the connect ring all
   derive from the same lattice rather than each inventing a look.
2. **Transparency over opacity** - show the system, not a veneer. No gradients anywhere, ever.
3. **Monochrome first; colour is a signal.** At most one accent per view, and only when it means something.
4. **Pure black canvas** (`#000000`), never dark grey. Dark mode is the default mode.
5. **Function is form.** Layout choices are ergonomic first.
6. **Radical reduction** - every element must earn its place. Nothing decorative.

Reference palette (Nothing OS): `#000000` canvas, `#0D0D0D`/`#1A1A1A`/`#242424` surfaces,
`#2E2E2E` border, `#FFFFFF` text, `#999999` secondary, `#555555` muted, `#FF3030` accent used sparingly.

Rules worth internalising: NDot-style display type only at large sizes; borders 1px, never thicker;
opacity transitions (never background flashes); no spring or bounce easing - cubic 150-400ms only;
labels uppercase at 0.06-0.10em tracking; no emoji in UI copy; no circular/rounded icon packs - dot-grid only.

## 1. Non-negotiables

1. **No font files.** All "display" type is the 5x7 dot-matrix drawn on a Compose `Canvas` (`DotText`,
   `GLYPHS` in `Nothing.kt`). Body and value text use the platform default; labels use monospace.
2. **Two type systems only:** `NType.label` (mono, 11sp, +1.2 letterspacing, always uppercased) and
   `NType.body`/`mono`/`title`. Never introduce a third size.
3. **Black and white carry the design.** Colour is a signal, not decoration - see the accent rules below.
4. **Hairline borders, never shadows or elevation.** `NCard` draws a 1dp border and a flat fill.
5. **Generous radii** (22-28dp for cards, `CircleShape` for controls). Nothing is square except rules and ticks.

## 2. Colour

| Role | Dark | Light | Source |
|---|---|---|---|
| `bg` | `#000000` | `#F0F0F0` | `DarkN` / `LightN` |
| `surface` | `#101010` | `#FFFFFF` | " |
| `surface2` | `#1B1B1B` | `#E6E6E6` | " |
| `line` | `#2A2A2A` | `#D4D4D4` | " |
| `text` | `#FFFFFF` | `#0A0A0A` | " |
| `dim` | `#8A8A8A` | `#78787A` | " |
| `accent` | red `#FF3B30` | red `#C8102E` | user setting |

Accents: `red` (default), `lime`, `amber`, `blue`, `mono`.

**Surfaces are tinted toward the accent** (`tint()` in `NTheme`, 3-9% depending on role). A single accent
dot on a pure black screen was invisible; tinting keeps the minimal look while making the colour felt
across the whole surface. Keep the amounts small - if a surface starts reading as *coloured* rather than
*tinted*, the percentage is too high.

**Accent means something.** Use it for: errors, expiry/limits, "live" and "active" states, destructive
actions, the connect ring when failed. Do **not** use it for ordinary labels or values - those are
`text`/`dim`.

## 3. Type rules

- `NLabel(text)` - section and field labels. Uppercases automatically. Always pass a short string.
- `DotText(...)` - for short, brand-like strings only: screen titles (`LIMOO`, `SERVERS`), the connect
  state (`ON`/`OFF`), empty states.
- **`DotText` must be width-bounded for anything variable.** It computes its width from the string, so a
  long value overflows its slot and the parent clips it. Use `maxWidth =` on `DotText`, or `DotTextFixed`
  for live values (speed, counters, uptime) which also locks the box so the layout never reflows.
- Long descriptive text is normal `Text` with `NType.body`, never dot-matrix. Mixed fonts in one row is
  what makes the UI look unresolved - pick one per row and commit to it.
- `maxLines = 1` + `TextOverflow.Ellipsis` on every row that can receive a server name or host.

## 4. Spacing and shape

- Screen padding `20.dp` horizontal. Card padding `16-20.dp`. Row padding `14.dp` vertical.
- Gaps between cards `8-10.dp`; between sections `22.dp` (`NRule`).
- Icons are almost absent. Overflow affordance is `NDots` (three drawn dots), not a text ellipsis and not
  a Material icon.
- `NBrackets` for corner ticks on framed/hero elements only.

## 5. Motion

- Haptics on every tap via `rememberTick()` (`LocalHaptics`, user-toggleable). Toggles use `LongPress`.
- `NSpinner` - a dot ring with one orbiting dot - is the only loading indicator. `NBusy` (inline, with a
  label) and `NBusyBlock` (centred, for sheets) wrap it.
- **Any action that takes more than ~300ms must show progress.** Imports and subscription fetches set
  `Actions.setBusy`, which shows `NBusy` under the header and blocks the confirm button.
- The connect ring is the centrepiece: dim = off, comet = connecting, full = connected, accent = error.

## 6. Components (`Nothing.kt`)

`NCard` `NButton` `NChip` `NSwitch` `NCheck` `NRadio` `NDots` `NRow` `NField` `NSearch` `NSheet`
`SheetRow` `ChoiceRow` `ToggleRow` `FieldRow` `NumberRow` `Divider` `NDivider`
`DotText` `DotTextFixed` `DotMeter` `DotBar` `DotStripView` `NSpinner` `NBusy` `NBusyBlock`
`NRule` `NBrackets` `NStat` `NReadout` `NGlyph` `NFadeDots` `NDotsProgress` `SubAllowance`

### Icons
There are no icon fonts. Icons are **dot-matrix glyphs** (`NGlyph`) or hand-built dot vectors in
`res/drawable` (`ic_launcher` = dot-grid "L", `ic_stat` = its status-bar reduction, `ic_tile` = dot ring).
Never introduce a Material icon here.

Add new UI as a component here rather than inline in a screen, so the vocabulary stays closed.

## 7. Screen structure

- Bottom `NavPill` (HOME / SERVERS / SETTINGS) - a floating capsule, never a Material bottom bar.
- Settings is a list of category rows that push a `Page`; `Page` renders a bounded `DotText` title.
- Long lists use `NCard` rows with swipe actions; destructive ones always offer undo via `Ui.say`.

## 8. Gotchas that have bitten us

- **Never call a Compose extension function fully qualified** (`androidx.compose.foundation.lazy.items(...)`
  does not resolve). Import it. This broke a build once already.
- `kotlinOptions` is deprecated in AGP 8.5+; use `kotlin { compilerOptions { ... } }`.
- `DotText` inside a `Row` without a width constraint will push siblings off screen.
- The Xray core's `initCoreEnv` base key must be 32 bytes as **unpadded base64url** (43 chars), not hex.
  See `CoreEngine.xudpBaseKey`.
- Traffic-stat keys from the core are **`uplink` / `downlink`**, not `up` / `down`. Getting this wrong
  fails silently - `CoreStats.parse` returns null and no counter appears at all.
- The delay probe needs `initCoreEnv` to have run (`CoreDelay` does it once per process) and must use
  `XrayConfigBuilder.buildProbe`, which has no inbounds - a probe that binds the SOCKS port collides with
  the running core.
- Ring/grid geometry must be derived from a single pitch and centred; two independent radii produce a ring
  that looks lopsided. Verify symmetry by mirroring the point set about both axes before shipping.
