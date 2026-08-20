# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2026-08-20

### Added

- **Radio playback is now optional in "Tune and play radio".** Merging the two actions into
  one entry removed the only way to change station without also starting the radio — which
  is what a rule that sets the station for later, or one that runs while the driver is
  listening to something else, actually needs. The action editor carries an "Enable radio"
  switch, on by default: turned off, the rule tunes and stops there. Rules saved before this
  release keep the merged behaviour, and turning the switch off on an existing action also
  removes the wait and the play step that followed it.

## [2.0.0] - 2026-08-15

### ⚠️ Breaking — existing users must install once more

- Application id changed from `com.mg4.tasker` to **`com.evsuite.tasker`**. Android treats
  this as a different app, so it does not update an existing install: it is added next to
  it and starts with no rules, no permissions and no history. Export what you need from the
  old app, install this one, check a rule while parked, and only then uninstall the old app.

### Added

- **"Ask for confirmation", an action that stops the rule when the driver says no.** A rule
  that opens the windows or unlocks the doors on arrival is right most of the time and wrong
  the once, and the driver is the only one who knows which this is. The action puts its own
  question full-screen — the text is yours — and the actions placed after it run only on
  "yes". Silence is a no: the prompt times out after 30 s and the branch stops there, because
  the actions behind a confirmation are exactly the ones nobody wanted applied unattended.
  A declined rule reads as *skipped* in the history, not as a failure; a rule nobody answered
  reads as failed, since it was left half-applied.
- **Saved places, for the location condition and the navigation action.** Coordinates are
  not something anyone types at the wheel. Any point in either editor can now be named and
  stored, then picked from a dropdown in every later rule — the same gesture as choosing a
  contact instead of a phone number. The list is EVTasker's own: the head unit's navigation
  app publishes neither a provider nor an intent for its favourites, so there is nothing to
  read from it. Its entries come from where the car actually is.

### Fixed

- **A new catalogue entry no longer stays invisible after an app update.** The support
  record survives an update, and the rule editor reads a name missing from it as "not
  supported on this car" — but a set written by an earlier build cannot name an entry that
  build did not have. Every action or condition added by an update was therefore hidden on
  any car that had ever run a support check, until the driver happened to run another one.
  A record that predates the installed version is now treated as no record, so the editor
  falls back to the live firmware matrix until the next check overwrites it.

### Changed

- **"Navigate to" gained the controls the location condition already had.** Use current
  position and Open map now sit under the destination field, alongside the saved places.
  The field is no longer prefilled with the car's position the way the location condition is:
  where the driver already is, is the one destination a rule never means — it stays one
  button away.

## [1.1.0] - 2026-08-10

### Added

- **"Ask for a profile", an action that hands the choice back to the driver.** "Apply a
  profile" decides alone, which is right for a rule that knows the answer and wrong for the
  ones that do not: arriving at a charger, the profile to apply depends on what the driver
  is about to do, and no condition reads that. The action asks EVProfile to open its own
  profile picker, and the driver taps. It needs EVProfile like the profile action does, and
  carries the same "when stopped only" mark — EVProfile refuses to put the picker in front
  of a moving driver. A refusal is never deferred to the next standstill: a question shown
  at the next red light would reach a driver who is no longer asking it.
- **Two Bluetooth conditions that tell a phone in the car from a phone merely near it.**
  "Phone connected" is a radio fact and answers yes for a phone left in the house while the
  car is parked outside it, which made arrival rules fire on the driveway. **Phone on
  board** is true only of a device still connected once the car has driven — a phone that
  stayed behind has dropped off the link by then. It is unreadable, and so unevaluable,
  until the car has actually moved; rules that must act at ignition should keep using
  "phone connected" gated on a vehicle signal. **Hands-free phone** is the one the head
  unit routes calls through: it answers from the first second and is what separates two
  phones both in range. The diagnostic screen reports both, and says "the car has not
  driven yet" rather than blaming the Bluetooth radio.

- **"else if" and "else" cases in a rule.** Cases are tried in the order written and exactly
  one runs: the first whose conditions hold, or the "else" when none did. An unreadable
  condition stops the rule where it stands instead of falling through to the next case or to
  the "else" — unreadable is not false, and falling through would write to the vehicle
  because a reading was missing. Each case is edited in its own window, conditions on one
  side and actions on the other; the rule screen shows one card per case, in evaluation
  order, with arrows to reorder the "else if" ones. Up to four "else if" cases per rule. The
  history names the case that ran, and the wait budget compares the longest case rather than
  the sum. A rule that names a physical button must name one in every case and takes no
  "else": the button decides which event stream feeds the whole rule, so a case without one
  would run on presses it never mentioned. Rules files gain `elseIf` / `elseActions` at format
  version 2; a file with no case still claims version 1 and stays readable by earlier builds,
  and a file whose content and version disagree is refused.

- **Wait** action (1 to 60 seconds), and reordering of a rule's actions with the arrows
  on each row. Actions run one after the other in the order shown, so a wait placed
  between two of them lets the first write land before the second is asked for
  (climate on, then fan level). A rule made only of waits is refused at save time, and one
  cycle may wait two minutes in total across all its rules — beyond that the wait is cut
  short and reported in the history, rather than applying later actions against a stale
  snapshot.
- Configuration tab. The speed threshold, the language and the rules file
  (export / import) moved there from the rules pane; the Rules tab is now the rule
  list and nothing else.

### Changed

- **The operator and the value of a condition are read at title size** (26sp) rather than
  body. "is equal to" and the value next to it are the two words that decide what a
  condition means, and they were the smallest text on the screen carrying them — in the
  closed drop-down and in its open list alike.
- **The picker and the Diagnostic tab no longer list an entry twice.** "Play the radio" is
  not a choice — "Tune and play radio" expands into it — and "Call a contact" is the
  deprecated alias kept for old saved rules. Both showed next to the entry they duplicate,
  giving two radio actions and two call actions. They are hidden from the two screens the
  driver reads, not from the engine or the exported debug report, where an imported rule can
  still carry either one.
- **The stored support record drops entries this build no longer defines.** It survives an
  upgrade, and an upgrade can delete catalogue entries — the four `STAR_*_PRESS` conditions
  became one `PHYSICAL_BUTTON`. Until the next check ran, the Diagnostic tab counted the
  removed ones as supported.
- **Crash reporting and paste upload now come from EVHardware** (`com.evsuite.hardware.diag`)
  instead of being carried here. The crash report gains the cause chain and a truncation
  that counts bytes rather than characters, so a report full of accented log lines no longer
  overshoots its own ceiling. Nothing changes on screen.
- **One "Call" action** instead of two. The same entry now takes a typed number or a name
  from the phone book; the field is both a search and a number entry. Rules saved with the
  old "Call a contact" action still load and still call — the entry is deprecated, not
  removed, because a deleted enum name deserialises to null.
- **A vehicle action is only attempted on a firmware generation its catalogue entry names.**
  An unidentified generation reports `firmware support not confirmed` instead of writing to a
  car nobody could recognise. The editor still offers everything, since hiding entries on a
  guess would be worse; the Diagnostic tab runs the same check and reports the same verdict.
- The first launch after an upgrade opens on the Diagnostic tab: a new build's verdicts about
  this car are what changed.
- The Diagnostic tab no longer claims "show a message" always reaches the driver: it reports
  the notification channel being off, which is what the executor does. Radio tuning now
  checks the vendor radio service, and webhooks and waits stop being reported as blocked by
  a vehicle layer they never touch.
- Dialog text raised to the suite's sizes (20sp body, 26sp title) instead of
  Material's phone defaults — the test result, the language list and every
  confirmation. The "Remove" button of a condition or an action and the weekday chips
  were also below the 16sp floor.
- Adopted the EVSuite design system. Colour, spacing, type and component styles now
  come from shared tokens (`values/colors.xml`, `values/dimens.xml`,
  `values/styles_ev.xml`), generated by `tools/sync-tokens.mjs` and specified in
  [DESIGN.md](DESIGN.md).
- Theme is now `Theme.Material3.DayNight.NoActionBar`: the app follows the vehicle's
  day/night setting, and the light palette is held to the same 7:1 contrast floor as
  the dark one.
- `ev_outline` raised from `#4A525B` to `#7A8492`. The old value was 2.25:1 against
  `ev_surface`, below the 3:1 floor for non-text UI, so the card border it was meant
  to provide effectively was not there.
- Launcher icon replaced with the EVSuite adaptive icon: charcoal tile, white glyph
  with a single red accent, product caption. Vector only — the five legacy density
  bitmap buckets are gone.
- README restructured to the shared EVSuite skeleton; table of contents is now
  generated by `tools/sync-docs.mjs`.
