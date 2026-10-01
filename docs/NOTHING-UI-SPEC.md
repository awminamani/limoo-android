# NOTHING-INSPIRED UI — AI AGENT DESIGN CONTRACT

> **Purpose:** This file is a visual and interaction contract for an AI coding agent. Read it before creating, redesigning, or styling any UI.
>
> **Important:** The target is a **Nothing-inspired** interface language, not a literal clone of Nothing's proprietary UI, fonts, icons, wallpapers, or assets. Do not copy proprietary assets. Recreate the *design principles* with original components.

---

## 0. THE MOST IMPORTANT RULE

**DO NOT REDUCE “NOTHING STYLE” TO A DOT-MATRIX FONT.**

A successful implementation must feel recognizable even when the dot-matrix type is completely removed.

The identity comes from a combination of:

1. **Extreme visual restraint** — mostly monochrome, with very little decorative noise.
2. **Hardware-inspired geometry** — grids, rings, segmented indicators, technical lines, precise alignment.
3. **Editorial composition** — oversized whitespace, strong hierarchy, asymmetrical but deliberate layouts.
4. **Clean modern typography** — ordinary readable text remains ordinary readable text.
5. **Dot/LED micrographics** — used selectively as a signature detail, not as the entire typography system.
6. **Transparent / layered surfaces** — especially for cards, widgets, controls, and secondary information.
7. **Tactile interaction** — subtle, smooth, purposeful motion instead of flashy effects.
8. **Functional decoration** — visual details should look like information, instrumentation, or hardware behavior.

### Absolute anti-pattern
Do **not** do this:

> black background + Ndot-looking font everywhere + red buttons + rounded cards = Nothing UI

That is a weak imitation.

Instead, think:

> **minimal editorial UI + precision hardware language + monochrome information architecture + selective dot graphics + intentional motion**.

---

# 1. VISUAL TARGET

The interface should feel:

- technical but friendly
- futuristic without looking like a sci-fi dashboard
- minimal without looking empty
- industrial without looking harsh
- playful in tiny details, serious in the main information hierarchy
- premium without gradients and glassmorphism overload
- calm and highly legible

Keywords:

`technical` · `minimal` · `hardware-inspired` · `monochrome` · `precise` · `editorial` · `modular` · `tactile` · `playful micrographics`

Avoid keywords like:

`cyberpunk` · `gaming` · `neon` · `hacker terminal` · `synthwave` · `generic glassmorphism`

The app should **not** look like a gaming RGB dashboard.

---

# 2. COLOR SYSTEM

## Default / Classic Mode

Use a restrained monochrome palette.

```text
Canvas / OLED black       #0A0A0A
Primary surface           #111111
Raised surface             #171717
Hairline                   #252525
Strong line                #373737
Primary text               #F2F2F2
Secondary text             #A0A0A0
Muted text                 #6B6B6B
Pure display white         #FFFFFF
Signal red                 #D71920
```

The exact values may be adapted for accessibility and platform conventions, but the **relationship** must remain:

- black dominates
- white provides hierarchy
- gray provides structure
- red is rare and meaningful

### Accent rule
Use red only for things that deserve attention:

- live state
- recording
- warning / urgent state
- notification indicator
- destructive action
- important system signal
- a tiny decorative hardware-like indicator

Do **not** make every primary button red.

Normal selected/active states should usually use **black/white inversion** rather than red.

Example:

```text
inactive    → dark surface + light label
active      → white surface + black label
alert       → tiny red signal
```

## Optional Adaptive Mode

Modern Nothing OS increasingly allows wallpaper-aware colors and tinted UI elements. If the product needs a more contemporary version, allow one restrained adaptive accent derived from the user's wallpaper.

Rules:

- keep the interface underneath predominantly neutral
- never turn the whole UI into a colorful theme
- use one generated accent family at a time
- maintain strong text contrast
- preserve the hardware-inspired monochrome base

---

# 3. TYPOGRAPHY — THIS IS WHERE MOST BAD IMPLEMENTATIONS FAIL

## Primary principle

**Readable UI text must use a normal modern sans-serif.**

Good choices when available:

- Geist
- Inter
- SF Pro / system sans
- Roboto
- platform-native equivalent

Use the closest available clean grotesk/sans if the project already has a typography system.

### Dot-matrix / segmented display type is LIMITED

Use dot-matrix styling for:

- large clocks
- timers
- large numerical readouts
- percentages
- counters
- model/version numbers
- tiny technical labels
- decorative hero numerals
- occasional micrographic text

Do **NOT** use it for:

- paragraphs
- navigation labels
- button labels
- form fields
- settings descriptions
- long headings
- body copy
- every number on every screen

The dot language should feel like a **hardware display**, not a font replacement.

### Typography hierarchy

Prefer large differences in scale instead of many font weights.

Suggested hierarchy:

```text
Display             48–96 px    bold / display style
Hero number         48–80 px    dot/technical display optional
Page heading        28–40 px    clean sans
Section heading     18–24 px    clean sans
Body                15–18 px    regular
UI label            12–14 px    medium / mono optional
Micro label         10–12 px    uppercase / tracking
```

Do not make every text element uppercase.

Use uppercase mainly for metadata, system labels, technical categories, and compact controls.

### Tracking

Use slightly generous tracking for tiny uppercase labels.

Do not use extreme letter spacing on normal text.

### Emphasis

Use hierarchy in this order:

1. size
2. position
3. weight
4. inversion
5. opacity
6. accent color

Do not solve hierarchy by making everything colorful.

---

# 4. THE GRID IS THE REAL SECRET

Everything should feel mechanically aligned.

Use a consistent spacing scale such as:

```text
4   micro gap
8   compact gap
12  small component spacing
16  standard spacing
24  card / section padding
32  major component spacing
48  major section spacing
64  hero spacing
96  large editorial spacing
```

Use one base unit and derive spacing from it instead of arbitrary values everywhere.

### Layout behavior

Favor:

- strong left alignment
- consistent gutters
- asymmetric compositions that still obey the grid
- large empty zones
- modular rectangular regions
- deliberate horizontal rules
- occasional full-width separators

Do not center everything.

A Nothing-inspired interface often looks more premium when the content is anchored to a precise grid rather than dumped into centered cards.

---

# 5. SHAPE LANGUAGE

## General shapes

Use:

- rectangles
- circles
- rings
- thin lines
- segmented bars
- grids
- radial indicators
- rounded rectangles with small radii when appropriate

Avoid:

- huge pill-shaped containers everywhere
- bubbly SaaS cards
- cartoon blobs
- excessive 20–32px corner radii
- floating colored gradients

### Corner radius

Prefer subtle radii:

```text
0 px   technical containers / separators
4 px   compact controls
8 px   normal cards
12 px  larger elevated surfaces
```

Do not round everything by default.

A square technical panel next to a softly rounded utility panel can create much more character than making every element identical.

---

# 6. SURFACES AND DEPTH

Depth should primarily come from:

- spacing
- contrast
- borders / hairlines
- transparency
- layering
- scale
- motion

Avoid heavy shadows.

Avoid generic:

```css
box-shadow: 0 20px 60px rgba(...)
```

on every card.

### Preferred surface stack

```text
background
  ↓
subtle textured / transparent section
  ↓
hairline border
  ↓
content
```

For a more modern Nothing-like treatment, translucent surfaces can be used, but keep them subtle. The effect should feel like a transparent physical material or frosted technical panel, not generic “web3 glassmorphism.”

---

# 7. LINES ARE A PRIMARY DESIGN ELEMENT

Hairlines are extremely important.

Use 1px lines for:

- section separation
- table structure
- card boundaries
- navigation grouping
- technical diagrams
- timeline / progress structure
- decorative alignment

Lines should generally be low contrast.

Do not create thick borders around every component.

A page can feel much more “Nothing” with carefully placed 1px separators and whitespace than with ten decorative cards.

---

# 8. ICONOGRAPHY

Icons should feel engineered, not generic emoji-like graphics.

Preferred characteristics:

- simple silhouettes
- consistent stroke or dot construction
- geometric proportions
- limited detail
- strong negative space
- visually aligned to the same grid as text

## Dot icons

Use dot-matrix icons for **special moments**:

- hero visuals
- empty states
- status indicators
- device-like controls
- playful utility screens

Do not convert the entire icon system into dots unless the product specifically benefits from it.

### Dot construction

If drawing dot graphics manually:

- use a square logical grid
- make dots circular
- keep dot size consistent within an icon
- keep spacing mechanically uniform
- avoid blurry raster scaling
- prefer SVG/CSS/canvas for scalability

Suggested logical grids:

```text
8×8   tiny interface symbol
9×9   normal dot icon
16×16 small technical illustration
25×25 hero / Glyph-inspired graphic
```

The exact grid can change; consistency matters more than copying a specific Nothing device's physical LED layout.

---

# 9. MICROGRAPHICS

This is one of the strongest ways to make the design feel authentic **without** abusing the dot font.

Use small functional-looking graphics such as:

- radial rings
- segmented progress arcs
- waveform traces
- tiny frequency bars
- signal indicators
- tiny coordinate/grid marks
- calibration ticks
- counters
- heartbeat-like pulses
- circular timers
- segmented meters
- tiny technical diagrams
- dot clusters
- geometric symbols

The key word is **micro**.

These graphics should support the information rather than become decoration everywhere.

Example:

```text
┌────────────────────────────────────┐
│ STATUS                         03  │
│                                    │
│       ◌────◌────◌                  │
│      /           \                 │
│     │      78     │                │
│      \           /                 │
│       ◌────◌────◌                  │
│                                    │
│ SIGNAL             ACTIVE  ●       │
└────────────────────────────────────┘
```

The visual idea is closer to **instrumentation** than to a sci-fi HUD.

---

# 10. CARDS

Cards should NOT be the default answer to every piece of content.

Use cards when content genuinely needs grouping.

Preferred card behavior:

- compact border or subtle translucent surface
- 8px-ish radius
- generous internal spacing
- strong title hierarchy
- small metadata line
- optional micrographic
- almost no shadow

Good:

```text
┌────────────────────────────────────┐
│ NETWORK                             │
│                                    │
│ Connected                    ●      │
│ 148 ms                              │
│                                    │
│ ───────────────────────────────     │
│ Last sync     09:42                 │
└────────────────────────────────────┘
```

Bad:

```text
╭─────────────────────╮
│ 🌈 BIG CARD         │
│ Huge gradient       │
│ giant pill button   │
│ ✨✨✨               │
╰─────────────────────╯
```

---

# 11. BUTTONS AND CONTROLS

Controls should feel physical and intentional.

### Primary button

Default:

```text
white/light surface
black text
minimal radius
strong contrast
```

Hover:

- small opacity shift
- slight translation if appropriate
- no explosive glow

Pressed:

- tiny scale reduction or translation
- fast response

### Secondary button

Use a transparent/dark surface with a thin border or hairline.

### Destructive / urgent button

This is where the red signal can appear.

### Switches

Prefer simple high-contrast switches with black/white inversion.

Do not color every ON state bright red.

---

# 12. NAVIGATION

Navigation should feel like part of the information system, not a giant app-store tab bar.

Use:

- clear spacing
- subtle dividers
- simple icons
- readable labels
- strong active-state inversion

For desktop/web:

- a narrow technical sidebar can work well
- the main content should have lots of breathing room
- avoid 10-level nested navigation

For mobile:

- prioritize the content
- keep navigation compact
- avoid giant floating nav pills unless there is a strong product reason

---

# 13. DASHBOARDS / DATA

Nothing-inspired data UI should look like a physical instrument translated into software.

Use:

- large numbers
- compact labels
- subtle grids
- segmented progress
- circular gauges
- sparse charts
- thin axes
- tiny metadata

Avoid:

- rainbow charts
- thick chart cards
- excessive legends
- dense enterprise-dashboard clutter
- every metric competing equally

### Signature metric pattern

```text
CPU LOAD

42%                         ◌
██████████░░░░░░░░░░

NORMAL                         09:42
```

The number is the hero. The graphic supports it.

---

# 14. EMPTY STATES

Do not use generic sad illustrations.

Use a small piece of visual instrumentation.

Examples:

- sparse dot constellation
- inactive circular gauge
- tiny segmented indicator
- technical line illustration
- understated system message

Example:

```text
        ·   ·
    ·
            ·

        NOTHING HERE
       WAITING FOR DATA
```

Use normal typography for the actual message.

---

# 15. RED SIGNAL SYSTEM

Red is not “the theme color.”

It is a **signal**.

Use it to communicate:

- REC
- LIVE
- NEW
- WARNING
- NEEDS ACTION
- ERROR
- IMPORTANT

A screen should be able to work perfectly without red.

When red is present, the user should naturally understand that something deserves attention.

A tiny red dot is often stronger than a giant red button.

---

# 16. MOTION DESIGN

Motion is part of the identity.

Nothing's current OS direction emphasizes smoother motion and more refined interaction, so the implementation should feel responsive rather than static. citeturn434437view0

### Motion principles

- fast initial response
- soft deceleration
- short distances
- subtle scale changes
- opacity changes
- restrained spring behavior
- no constant animations

Suggested ranges:

```text
micro interaction     120–180 ms
normal transition     180–280 ms
large transition      280–450 ms
```

### Good motion

- button depresses by 1–2px
- panel slides 8–20px
- indicator fills smoothly
- dot cluster rearranges subtly
- menu fades + translates slightly
- number changes animate smoothly

### Bad motion

- giant bouncing panels
- infinite glowing borders
- particles everywhere
- neon pulse animations
- excessive parallax
- 1-second animations for ordinary buttons

### Loading

Use a technical signal, not a generic spinning loader where possible.

Examples:

```text
[••••••••••░░░░░]

or

◌ → ◍ → ● → ◍ → ◌
```

---

# 17. BACKGROUNDS

Default backgrounds should be visually quiet.

Good options:

- near-black
- warm white / off-white
- subtle grain
- very faint grid
- very faint dot texture
- restrained technical pattern

The texture should be almost invisible until the user looks for it.

Never let the background compete with the main content.

---

# 18. IMAGES / MEDIA

When photography is used:

- let the image remain the visual hero
- surround it with minimal UI
- use technical metadata sparingly
- avoid placing huge gradient overlays over every image

Good pattern:

```text
IMAGE

01 / 08
NIGHT DRIVE
24.09.26
```

The metadata should feel like a device readout.

---

# 19. COPY / CONTENT STYLE

Interface copy should be concise.

Prefer:

```text
CONNECTED
SYNCED
READY
OFFLINE
LAST UPDATE
NETWORK
DEVICE
STORAGE
```

Over:

```text
Your device is currently connected and everything is working perfectly!
```

But **do not** make every sentence robotic. Human-facing explanatory text should remain natural.

---

# 20. RESPONSIVE DESIGN

The system must preserve the same visual language at every size.

### Mobile

- fewer simultaneous modules
- large tap targets
- strong vertical rhythm
- compact technical metadata
- edge-to-edge sections where useful

### Tablet

- split information into modules
- use larger whitespace
- increase grid structure

### Desktop

- editorial composition
- asymmetric columns
- more negative space
- technical sidebar / status rail when useful
- larger display numbers and micrographics

Do not simply stretch the mobile layout onto desktop.

---

# 21. ACCESSIBILITY

The style must not sacrifice usability.

Minimum requirements:

- readable body text
- sufficient contrast
- visible focus state
- touch targets around 44px where applicable
- never communicate important information through red alone
- respect reduced-motion preferences
- do not hide essential labels behind decorative iconography

The dot style is decorative/instrumental; ordinary content must stay easy to read.

---

# 22. COMPONENT RECIPE

Every component should answer these questions:

### 1. What is its information hierarchy?

What is the one thing the user should notice first?

### 2. What is its hardware metaphor?

Is it a meter, display, panel, indicator, instrument, list, switch, etc.?

### 3. Where is the signature detail?

Use one small distinctive element rather than decorating every corner.

### 4. Does it work without the dot font?

If removing the dot typography destroys the entire visual identity, the design is too dependent on the font.

### 5. Is the decoration functional?

If the answer is “no, it just looks cool,” reduce it by ~50%.

---

# 23. SCREEN COMPOSITION TEMPLATE

A strong generic screen can follow this pattern:

```text
┌─────────────────────────────────────────────────────────────┐
│ AREA / PRODUCT                              09:42    ●      │
│─────────────────────────────────────────────────────────────│
│                                                             │
│ PRIMARY HEADING                                             │
│ Short supporting line                                       │
│                                                             │
│                       HERO                                  │
│                        78                                    │
│                  CURRENT STATUS                              │
│                                                             │
│───────────────────────┬─────────────────────────────────────│
│ SECONDARY MODULE      │ TECHNICAL MODULE                    │
│                       │                                     │
│  CONNECTED       ●    │  03 / 08                            │
│                       │  ███████░░░                          │
│                       │                                     │
│───────────────────────┴─────────────────────────────────────│
│                                                             │
│  LAST UPDATE                               09:42:17         │
└─────────────────────────────────────────────────────────────┘
```

This is a **composition reference**, not a literal template.

---

# 24. THE “NOTHING TEST”

Before considering the UI complete, the agent MUST check:

### A. Remove the dot font
Does the interface still feel distinctive?

### B. Remove the red
Does the hierarchy still work?

### C. Remove all shadows
Does the structure still have depth?

### D. Remove half the cards
Does the screen become cleaner?

### E. Remove half the decorative graphics
Is the product easier to understand?

### F. Squint at the screen
Is there one obvious visual hierarchy?

### G. Look at a single component
Does it look like part of a coherent system rather than a generic UI kit?

If the answer to several of these is “no,” redesign the composition rather than adding more effects.

---

# 25. ANTI-PATTERNS — DO NOT GENERATE THESE

Never default to:

- giant glowing red buttons
- purple/blue neon gradients
- cyberpunk HUDs
- excessive glassmorphism
- giant rounded pills
- floating blob backgrounds
- every icon rendered as dots
- every number rendered in dot font
- fake terminal UI everywhere
- random decorative coordinates
- meaningless `01 02 03` labels
- dozens of tiny badges
- excessive uppercase text
- huge shadows
- bouncing micro-interactions
- excessive blur
- rainbow gradients
- “AI dashboard” visual clichés
- generic shadcn cards with a Nothing font pasted on top

**Especially avoid the last one.**

The UI should not look like a standard Tailwind/shadcn dashboard that has merely been reskinned.

---

# 26. IMPLEMENTATION PRIORITY

When building the interface, implement in this exact order:

```text
1. layout / grid
2. spacing
3. typography hierarchy
4. surfaces + hairlines
5. component geometry
6. icon system
7. micrographics
8. motion
9. sparse accent color
10. decorative polish
```

Never start with gradients, fonts, or animations.

---

# 27. IF THE AGENT IS USING A UI FRAMEWORK

Whatever the framework, create reusable design tokens first.

For example:

```css
:root {
  --bg: #0A0A0A;
  --surface: #111111;
  --surface-raised: #171717;
  --line: #252525;
  --line-strong: #373737;
  --text: #F2F2F2;
  --text-secondary: #A0A0A0;
  --text-muted: #6B6B6B;
  --signal: #D71920;
  --radius-sm: 4px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 24px;
  --space-6: 32px;
  --space-7: 48px;
  --space-8: 64px;
}
```

Adapt the tokens to the platform. Do not blindly copy these values if the host platform has a stronger native accessibility or spacing convention.

---

# 28. PLATFORM-SPECIFIC NOTES

## Web

Prefer:

- CSS grid
- CSS custom properties
- SVG for micrographics
- CSS transitions
- `prefers-reduced-motion`
- responsive composition rather than breakpoint-specific redesigns

## Android / Jetpack Compose

Use:

- Material components only as structural primitives when useful
- custom tokens for shapes/colors/typography
- Compose Canvas / vector drawables for original dot graphics
- subtle `animate*AsState` / spring motion

Do not make the application look like default Material with a black background.

## Flutter

Use:

- ThemeData / ThemeExtension for tokens
- CustomPainter for dot/micrographics
- AnimatedContainer / implicit animations for small interactions

Do not rely exclusively on stock Material widgets.

## iOS / SwiftUI

Use:

- custom `Shape`
- custom `Canvas`
- semantic color assets
- native typography for readable UI
- dot display graphics only where appropriate

---

# 29. ASSET RULES

Do not download or bundle Nothing's proprietary logos, exact UI assets, wallpapers, or proprietary fonts unless the project has explicit rights/license to use them.

When the product needs the aesthetic, recreate it with:

- original SVGs
- original geometric icons
- CSS/canvas dot graphics
- licensed fonts
- system fonts
- original motion

The result should be **inspired by the design language**, not dependent on copied brand assets.

---

# 30. CURRENT-DIRECTION NOTE

Nothing's current public OS direction has evolved beyond the original strict monochrome aesthetic. Nothing OS 5.0 describes a cleaner interface, wallpaper-aware colours, tinted app icons, transparent widgets, updated icons, refined layouts, a new Geist typeface, and smoother motion. citeturn434437view0

Nothing's Phone (3) materials also frame the Glyph system as a functional communication layer: the Glyph Matrix is used for notifications, timers, volume, NFC, games, and other interactions rather than being purely decorative. citeturn274384search2turn274384search0

Therefore, this spec deliberately treats **dot-matrix graphics as one part of the system**, not the whole system.

---

# 31. FINAL AI-AGENT PROMPT

Use this section as the concise instruction if the entire document is too long for a model context window:

> **Design the product as a premium Nothing-inspired UI, but do NOT imitate Nothing by simply applying a dot-matrix font.**
>
> Build the visual identity from a strict grid, generous negative space, monochrome surfaces, thin hairlines, precise geometric components, editorial typography, hardware-inspired micrographics, selective dot/LED graphics, subtle transparency, and restrained tactile motion.
>
> Normal UI text must remain highly readable in a clean sans-serif. Use dot-matrix typography only for selected display values, counters, timers, technical readouts, and decorative moments.
>
> Use black/near-black as the main dark canvas, soft whites for hierarchy, gray for structure, and a very sparse red signal color only for alerts/live states/important indicators. Do not make every button red.
>
> Prefer rectangles, circles, rings, segmented indicators, thin lines, grids, and small technical diagrams. Use modest corner radii and almost no heavy shadows. Do not use neon gradients, cyberpunk HUDs, giant pills, excessive glassmorphism, or generic SaaS cards.
>
> Layout comes before decoration: establish the grid, spacing, typography, and hierarchy first; then add micrographics and motion. Every decorative element must have a reason to exist.
>
> The interface must still feel distinctly designed after removing the dot font and the red accent. If it looks like a generic Tailwind/shadcn dashboard with a dot font pasted over it, redesign it.

---

# SOURCES / RESEARCH BASIS

The following sources were used to ground this specification:

1. **Nothing — Nothing OS 5.0**  
   Official product/design information covering cleaner UI, wallpaper-aware colors, tinted app icons, transparent widgets, Geist typography, updated icons/layouts, Micrographics, and smoother motion.  
   https://nothing.tech/nothing-os

2. **Nothing — Phone (3)**  
   Official product information covering Glyph Matrix, functional light-based interactions, notifications, timers, volume visualization, NFC, and the broader “intentional aesthetics” direction.  
   https://nothing.tech/products/phone-3

3. **Nothing Support — What is the Glyph Interface?**  
   Official explanation of Glyphs as a communication/interaction system using designed light patterns.  
   https://support.nothing.tech/hc/en-us/articles/16770135458193-What-is-the-Glyph-Interface

4. **Vibe-Nothing-UI-Design — DESIGN.md**  
   Community-created Nothing-inspired design system useful for cross-checking practical implementation patterns such as monochrome hierarchy, selective signal color, dot-matrix micrographics, and component rules. It is explicitly an independent, Nothing-inspired project rather than an official Nothing design system.  
   https://github.com/wangbh030722/vibe-nothing-ui-design/blob/main/DESIGN.md

---

# ONE-SENTENCE NORTH STAR

> **Make it feel like a beautifully engineered physical device became software — not like a website put on a black background.**
