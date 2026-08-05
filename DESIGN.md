---
name: MG4Suite
description: One design system for the MG4 app suite — a driver-facing instrument surface, not a phone UI parked in a car.
colors:
  background: "#F2F4F7"
  background-dark: "#0B0D0F"
  surface: "#FFFFFF"
  surface-dark: "#15181C"
  surface-raised: "#E4E8ED"
  surface-raised-dark: "#272C33"
  outline: "#737F8F"
  outline-dark: "#7A8492"
  text-primary: "#101418"
  text-primary-dark: "#F4F7FA"
  text-secondary: "#414B55"
  text-secondary-dark: "#C6CFD8"
  accent: "#044C63"
  accent-dark: "#7FD4FA"
  on-accent: "#FFFFFF"
  on-accent-dark: "#04121A"
  ok: "#09501F"
  ok-dark: "#8FE6A6"
  warn: "#6B3A00"
  warn-dark: "#FFC978"
  error: "#8F0F18"
  error-dark: "#FFACAC"
  icon-tile: "#2A2A2A"
  icon-glyph: "#F4F7FA"
  icon-mark: "#E0424C"
typography:
  display:
    fontFamily: "Roboto, sans-serif"
    fontSize: "32sp"
    fontWeight: 500
  title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "26sp"
    fontWeight: 500
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "20sp"
    fontWeight: 400
  secondary:
    fontFamily: "Roboto, sans-serif"
    fontSize: "17sp"
    fontWeight: 400
  label:
    fontFamily: "Roboto, sans-serif"
    fontSize: "16sp"
    fontWeight: 700
    letterSpacing: "0.12"
rounded:
  md: "12dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "16dp"
  lg: "24dp"
  touch: "72dp"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    height: "{spacing.touch}"
    padding: "0 24dp"
  button-outlined:
    backgroundColor: "transparent"
    textColor: "{colors.accent}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    height: "{spacing.touch}"
    padding: "0 24dp"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "16dp"
  section-header:
    textColor: "{colors.accent}"
    typography: "{typography.label}"
---

# Design System: MG4Suite

Applies to **MG4 Control**, **MG4 Tasker**, **MG4 ABRP Uploader**, **MG4 Simple Launcher**,
**MG4 Swipe Launcher** and the shared **MG4Hardware** library. This file is the normative
source; a copy lives at the root of every suite repository and they are kept identical.

## Overview

**Creative North Star: "The Instrument Binnacle"**

These are not phone apps that happen to run in a car. They are extensions of the
instrument panel, and they are judged the way a gauge is judged: can the driver read it
in one glance, at 70 cm, through a windscreen reflection, without thinking about it. Every
decision below follows from that. The suite is quiet, dense with information but never
busy, and it never asks for attention it hasn't earned — an MG4 app that animates,
celebrates, or decorates is competing with the road.

The visual language is flat, high-contrast and typographic. Depth comes from tonal
layering and a hairline outline, never from shadow. Colour carries meaning — accent,
ok, warn, error — and is never decorative. The one place personality is allowed is the
launcher icon, which is why the icon system gets its own section.

The suite is deliberately unlike consumer Android: bigger type, bigger targets, fewer
elements per screen, no gesture-only affordances, no ambient motion. Where Material 3
defaults conflict with legibility in daylight, legibility wins and the override is
commented in the theme.

**Key characteristics**

- Every text colour clears **7:1 (WCAG AAA)** on every surface it can land on. 4.5:1 is
  not enough once sunlight hits the glass.
- Minimum touch target **72dp**, everywhere, including dialog buttons and switches.
- Minimum type size **16sp**. There is no "small" tier.
- Flat surfaces, tonal layering, one hairline outline. No shadows, no gradients.
- Colour is never the only carrier of meaning; a text label always accompanies it.
- Landscape-first. Portrait is not a supported orientation on the head unit.

## Colors

A neutral blue-grey ladder carrying one cyan accent and three status hues. The suite is
**DayNight**: `values/` holds the light palette, `values-night/` the dark one, and both
are held to the same 7:1 floor. Token names are identical in both files — a layout never
references a light or dark colour, only a role.

Every ratio below is measured, not estimated. Re-run the check after any change:
`node tools/contrast.mjs` (see *Do's and Don'ts*).

### Neutral

| Token | Light | Dark | Role |
|---|---|---|---|
| `mg4_background` | `#F2F4F7` | `#0B0D0F` | The window. Never holds content directly. |
| `mg4_surface` | `#FFFFFF` | `#15181C` | Cards, panes, list rows. |
| `mg4_surface_raised` | `#E4E8ED` | `#272C33` | Buttons, chips, selected rows, dialogs. |
| `mg4_outline` | `#737F8F` | `#7A8492` | Hairline on every card. 4.07:1 / 4.70:1. |
| `mg4_text_primary` | `#101418` | `#F4F7FA` | 18.5:1 / 16.6:1 on surface. |
| `mg4_text_secondary` | `#414B55` | `#C6CFD8` | 7.2:1 worst case (on `surface_raised`). |

### Primary

- **Instrument Cyan** (`mg4_accent`, `#044C63` light / `#7FD4FA` dark): section headers,
  selected state, links, the active value in a control. 7.7:1 worst case in light, 10.8:1
  in dark. Light mode uses a deep cyan rather than tinting the dark one — a pale cyan on
  white is 1.5:1 and disappears entirely in daylight.
- **On-accent** (`mg4_on_accent`, `#FFFFFF` / `#04121A`): the only colour permitted on a
  filled accent surface.

### Status

Never used alone. Each is paired with a text label, because a driver glancing sideways
reads shape and text faster than hue, and because ~8% of male drivers cannot separate
these three by colour at all.

- **OK** (`mg4_ok`, `#09501F` / `#8FE6A6`) — the rule ran, the write landed, the upload succeeded.
- **Warn / refused** (`mg4_warn`, `#6B3A00` / `#FFC978`) — the speed gate refused a write,
  a deferred action is queued, firmware support is unknown.
- **Error** (`mg4_error`, `#8F0F18` / `#FFACAC`) — the operation failed.

### Pressed state

- `mg4_pressed` (`#14000000` / `#1FFFFFFF`) and `mg4_pressed_strong` (`#29000000` /
  `#33FFFFFF`). Alpha overlays on the surface beneath, not a separate hue, so a pressed
  card still reads as the same card.

### Named rules

**The Daylight Rule.** 7:1 or it does not ship. AA (4.5:1) is a phone-in-a-hand
standard; the MG4 screen is read through a reflection with the sun behind the driver, and
4.5:1 collapses to unreadable there. This is the single constraint that has forced the
most rewrites in this codebase — `#9EA6AE` secondary text (6:1) and the original
`#4A525B` outline (2.25:1) both shipped and both had to be replaced.

**The Meaning Rule.** A colour outside the neutral ladder must encode state. If it is
there to look nice, delete it. This is why the suite has one accent and not a palette.

**The Role Rule.** Layouts reference roles (`mg4_surface`), never values and never
theme-specific names. A layout that needs to know whether it is day or night is a bug.

## Typography

**Family:** Roboto — the AAOS 9 system face. No bundled fonts: an APK on a vehicle earns
every kilobyte, and a missing glyph in a six-locale app is a worse failure than a generic face.

**Character:** Neutral to the point of invisibility. The type is a readout, not a voice.
Weight and size carry hierarchy; italics and letterspacing are used once each, on purpose.

### Hierarchy

| Role | Size | Weight | Purpose |
|---|---|---|---|
| **Display** (`text_display`) | 32sp | Medium | A single number or word that is the whole point of the screen — a battery percentage, a drawer title. At most one per screen. |
| **Title** (`text_title`) | 26sp | Medium | Screen and card titles. |
| **Body** (`text_body`) | 20sp | Regular | Everything the driver actually reads. Also the size of every button label. |
| **Secondary** (`text_secondary`) | 17sp | Regular | Supporting detail, timestamps, units. |
| **Label** (`text_label`) | 16sp | Bold, `0.12` tracking | Section headers, in accent, uppercase-adjacent. The floor. |

### Named rules

**The 16sp Floor.** Nothing renders below 16sp. Not captions, not units, not footnotes.
Below that, contrast stops compensating for reflection and viewing distance, and the
information may as well not be on screen. If content does not fit at 16sp, there is too
much content.

**The One Display Rule.** One `text_display` element per screen. Two competing 32sp
numbers is two glances, and a glance is the budget.

## Layout

**Target.** MG4 Electric head unit: 12.8" HD centre display, AAOS 9 on MT2712, landscape,
viewed at ~70 cm. DPI is user-adjustable per app on this platform, so everything is
expressed in `dp`/`sp` and nothing assumes a pixel size. Portrait is not supported and is
not laid out for.

**Spacing scale.** `4 / 8 / 16 / 24dp` (`spacing_xs` … `spacing_lg`). Screen edges get
`spacing_lg`. Nothing between two rows is smaller than `spacing_sm` — tighter than that
and two targets read as one.

**Touch.** `touch_target` = **72dp** minimum for anything tappable, including dialog
buttons, switches and list rows. Android's 48dp guidance assumes a phone held still in a
hand; this screen is at arm's length in a moving vehicle. Adjacent targets are separated
by at least `spacing_sm`, so a missed tap is a miss, not a wrong action.

**Two-pane landscape.** Wide screens use a list/detail split rather than a stack — the
horizontal space exists, and a driver who has to navigate back to see context has lost
the context. `pane_list_width` = 380dp (wide enough for a full rule name), `pane_rail_width`
= 300dp for label-only rails. The detail pane takes the remainder and is the primary area.

**Grids.** Tile grids compute their column count from `catalog_tile_min_width` (240dp)
rather than hardcoding a number, so a DPI change re-flows instead of clipping. Tiles are
`catalog_tile_height` = 128dp — twice the touch minimum, so a three-line label plus a
qualifier fits without pushing the next row.

**Density.** Fewer, larger elements. If a screen needs a scroll before the fifth item, the
screen is wrong, not the scroll.

**App-local dimensions are allowed** where a surface genuinely differs (launcher tiles,
settings margins) — but they are declared in the app's `dimens.xml` with a comment saying
why, and they never redefine a suite token.

## Elevation & Depth

**There are no shadows.** Not one, in any app. A shadow on this display is a grey smear
that costs a composite pass and reads as dirt under sunlight. Depth is tonal:
`background` → `surface` → `surface_raised`, three steps, plus a 1dp `mg4_outline`
hairline on every card.

The hairline is not decoration. `surface` and `surface_raised` differ by roughly one
tonal step; in direct sun that difference vanishes and the card boundary goes with it.
The outline is what survives.

State is expressed by the pressed overlay, never by lift.

## Shapes

One radius: **12dp** (`mg4_corner`), on cards, buttons, chips, tiles and dialogs. Not two,
not a scale. A single radius is what makes five apps by different hands read as one suite,
and it is the cheapest consistency there is.

Borders are 1dp `mg4_outline`. No dividers where a gap will do — `spacing_md` between two
cards separates them better than a line, and costs no ink.

The launcher icon is the one place a different silhouette appears: a squircle tile, which
is the platform's shape, not ours.

## Components

Component styles are named identically in every app so a screen can be moved between
repositories without a rename: `Theme.MG4<App>`, `Widget.MG4.*`, `Text.MG4.*`,
`ThemeOverlay.MG4.*`.

### Buttons
- **Shape:** 12dp radius.
- **Primary** (`Widget.MG4.Button`): filled accent, `on_accent` label, `text_body` (20sp),
  min height 72dp, `spacing_lg` horizontal padding.
- **Outlined** (`Widget.MG4.Button.Outlined`): 1dp accent border, accent label, same metrics.
- **Dialog** (`Widget.MG4.DialogButton`): text button, but pulled up to the same 72dp
  target and 140dp min width. Dialog buttons are the smallest thing in any app and the
  most likely to be mis-tapped.
- **Pressed:** `mg4_pressed_strong` overlay. No ripple travel, no scale, no lift.

### Cards / containers
- 12dp radius, `mg4_surface`, 1dp `mg4_outline`, `spacing_md` internal padding.
- Selected state: background steps to `mg4_surface_raised` **and** the leading label
  turns accent. Two signals, because one is a colour.

### Switches
- `Widget.MG4.Switch`, 72dp min height, `text_body` label. A switch that needs a precise
  tap is a switch that gets missed at 90 km/h.

### Section headers
- `Text.MG4.SectionHeader`: 16sp, bold, `0.12` tracking, accent. The only tracked type in
  the suite; the tracking is what lets it work at the size floor without shouting.

### Dialogs
- `ThemeOverlay.MG4.Dialog` on `mg4_surface_raised`. Material's default dialog palette is
  a grey-mauve tuned for indoors and it is the first thing to wash out; every app overrides it.
- A picker that fills the screen (`Theme.MG4.Picker`) is not floating and does not dim:
  `windowIsFloating=false`, `backgroundDimEnabled=false`. In landscape, a bottom sheet
  leaves a third of the height, which means one column and a scrollbar by the fifth entry.
  There is nothing behind a picker worth seeing.
- A confirmation or warning with explanatory content is a **dedicated full-screen
  window, never a popup**. Put its explicit confirm and cancel actions together at the
  upper-right, consistently with the suite's editor windows, keep both at the 72dp touch
  minimum, and let the content use all remaining space. Reserve a compact dialog for a
  short, single-decision interruption whose complete message remains immediately readable
  at 70 cm.

### Lists
- Row height ≥ 72dp, `mg4_surface`, `spacing_sm` gaps. No swipe-to-delete: a gesture with
  no visible affordance and no undo does not belong in a vehicle. Destructive actions are
  a button plus a confirmation.

## Iconography

The launcher icon is the suite's signature and the one place it is allowed to have fun.

**The system.** Adaptive icon, 108dp, safe zone 66dp centred. Three fixed elements:

1. **Tile** — flat `#2A2A2A` charcoal. No gradient. Identical in every app, and identical
   in light and dark: this is the tile MG4 Control has always had, and it is what makes a
   row of suite apps read as a row.
2. **Glyph** — `#F4F7FA`, geometric, 5dp stroke, round caps and joins, with exactly one
   `#E0424C` red element as the accent (3.45:1 on the tile — it is a mark, not text).
   The glyph says what the app does at 48px. MG4 Control's glyph is the `MG⁴` lockup: it
   is the flagship, so it wears the suite mark.
3. **Caption** — the product word beneath the glyph, `#C6CFD8`, small caps, `0.18` tracking.

**The glyphs**

| App | Glyph |
|---|---|
| MG4 Control | `MG⁴` lockup — white letters, red superscript 4 |
| MG4 Tasker | A branch: one input, a condition, two outcomes |
| MG4 ABRP Uploader | An upward arrow leaving two broadcast arcs |
| MG4 Simple Launcher | A four-tile grid, one tile red |
| MG4 Swipe Launcher | Two chevrons and a swipe trail |

**Rules.** Vector (`VectorDrawable`) only — the same file serves every density and costs
one file instead of five PNGs. Every icon ships a `<monochrome>` layer for themed icons.
No photographic content, no bevel, no drop shadow, no stock clipart. If a glyph is not
legible at 48px in greyscale, it is the wrong glyph.

## Nomenclature

**Product names** are `MG4 <Word>` — space, title case: *MG4 Control*, *MG4 Tasker*,
*MG4 ABRP Uploader*, *MG4 Simple Launcher*, *MG4 Swipe Launcher*. This is what
`app_name` holds and what the driver sees under the icon.

**Repositories, modules and directories** are `MG4<Word>` — no space: `MG4Control`,
`MG4Tasker`, `MG4ABRPUploader`, `MG4SimpleLauncher`, `MG4SwipeLauncher`, `MG4Hardware`.

**Package ids** are `com.mg4.<lowercase>`; a family shares a segment
(`com.mg4.launcher.simple`, `com.mg4.launcher.swipe`).

**Resources** are `snake_case` with a suite prefix when the token is shared
(`mg4_surface`, `mg4_corner`) and unprefixed when app-local (`catalog_tile_height`).
Styles are `Theme.MG4<App>`, `Widget.MG4.<Thing>`, `Text.MG4.<Role>`.

**The suite** is *MG4Suite* — one word, when referring to all of it.

## Documentation

Every repository carries the same files, in the same order, with the same headings.

| File | Contents |
|---|---|
| `README.md` | The one below. English. |
| `DESIGN.md` | This file, verbatim. |
| `AGENTS.md` | Repo-specific agent context. |
| `CONTRIBUTING.md` | How to build, test, submit. |
| `SECURITY.md` | Threat model, disclosure. |
| `DISCLAIMER.md` | Vehicle-safety disclaimer. |
| `CHANGELOG.md` | Keep a Changelog format. |
| `LICENSE.md` | Licence text. |

**README skeleton** — sections in this order, omitting any that do not apply:

```
# MG4 <Name>
<one-line description>
<badges>
## Contents
## Screenshots
## Overview
## Features
## Requirements
## How it works
   … app-specific detail sections belong here, and only here
## Install
## Configuration
## Building
## Project layout
## Project documents
## Security
## Contributing
## Legal
```

The `## Contents` table of contents is generated from the headings, not hand-written:
`node tools/sync-docs.mjs`. A hand-written TOC goes stale the first time a heading
is renamed, and a dead anchor in a README is a small lie about the project.

Translations live in `README.<lang>.md` and are linked from the top of the English
README — never interleaved into it. A bilingual README doubles the size of every future edit.

## Constraint history

Why the rules above are the shape they are. Each of these was paid for.

- **7:1, not 4.5:1.** Secondary text shipped at `#9EA6AE` (6:1) and was unreadable in
  daylight. The floor moved to AAA and the whole ladder was re-derived.
- **The outline exists because tonal layering alone failed.** `surface` and
  `surface_raised` are one step apart and merge under sun. The border was added — then had
  to be raised from `#4A525B` (2.25:1) to `#7A8492` (4.70:1), because a border below the
  3:1 non-text floor is a border that isn't there.
- **72dp, not 48dp.** Google's target assumes a hand-held phone. Missed taps on a moving
  vehicle drove it to 72dp, and dialog buttons and switches were the last holdouts.
- **16sp floor.** Anything smaller stopped being legible at 70 cm through a reflection.
- **Full-screen pickers.** A bottom sheet in landscape left a third of the height: one
  column, scrolling by the fifth entry. The picker became a full-screen non-floating window.
- **No shadows.** Cost a composite pass on MT2712 and read as smudge in sun.
- **Dark-first, DayNight second.** The suite ran dark-only for a long time because a light
  background facing the driver at night is glare. It is now DayNight across all five apps
  by explicit decision — which means the light palette carries the same 7:1 obligation
  the dark one always had, and the pale dark-mode accent could not simply be reused.
- **Colour never carries meaning alone.** Verdict rows show a word as well as a hue.
- **Vector icons, no PNG sets.** Five density buckets per app, for an icon, on a vehicle
  where APK size is a stability concern.
- **AAOS 9, MT2712, must not destabilise the vehicle.** Everything above is downstream of
  this. When a design choice and stability disagree, stability wins and the design choice
  gets a comment explaining what it gave up.

## Do's and Don'ts

### Do
- **Do** verify contrast numerically before committing a colour. Every ratio in this file
  is measured; new ones must be too.
- **Do** put the reason in the resource file. Every override of a Material default in this
  suite carries a comment saying what would break without it. That comment is why the rule
  survives the next refactor.
- **Do** reference roles (`@color/mg4_surface`, `@dimen/touch_target`), never literals.
  A hex value in a layout is a bug.
- **Do** give every state two signals — colour plus text, or colour plus position.
- **Do** design landscape first, and check that nothing needs a scroll before the fifth item.
- **Do** name new resources with the suite prefix if another app could want them.

### Don't
- **Don't** add a shadow, gradient, or elevation. Depth is tonal.
- **Don't** introduce a second accent hue. One accent, three status colours, that is the
  whole palette.
- **Don't** ship type below 16sp or a target below 72dp, including in dialogs.
- **Don't** use `Theme.Material3.Dark` or `.Light` directly — always `.DayNight`, so both
  palettes stay wired.
- **Don't** rely on a gesture with no button fallback. No swipe-to-delete, no long-press-only.
- **Don't** animate anything ambient. Motion is confirmation of a touch, or it is absent.
- **Don't** put a light background behind a full-screen surface at night without checking
  it against the dark palette — the driver's night vision is a safety property.
- **Don't** hardcode a pixel size or assume a DPI. This platform lets the user change it.
