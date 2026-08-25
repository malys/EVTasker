# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Each of the four windows on its own, to read and to set, alongside the existing
  all-windows action — which stays, because closing the glass is one gesture that must not be
  able to leave three shut and one open.

### Changed

- **The window actions are now gated in the opening direction.** Closing stays allowed while
  moving — a rule that shuts the windows when it starts raining on the motorway is the reason
  those actions exist — but opening glass at speed goes through the standstill gate like any
  other vehicle write. A position the car will not report gates the write, since an unknown
  direction is not a safe one. The editor says which half is refused instead of claiming the
  whole action needs a standstill.
- **Remaining range** and **odometer** conditions, and **weather** — the head unit's own
  weather service, asked for where the car is, with no account and no traffic of ours. The
  phrase is matched as a fragment ("rain" catches "Light rain") and comes back in the head
  unit's language.

- The climate and charging readings the vendor services already answered and nothing could
  reach: ECON, the passenger target temperature, both defrosters, scheduled charging, the two
  ends of the charging window and battery pre-heating. Two matching writes, **ECON mode** and
  **passenger temperature**.
- **Charging state** as its own condition. *Charging* stays a flag and keeps its meaning; the
  state is what can say "plugged in and not charging", which the flag cannot.
- **Front door open** on SWI133, reading the door status the volume-drop watcher already polls.
- A clock-time control, used by the two ends of the charging window. Minutes behind a 0–1439
  slider was not something a driver could answer.
- **Enable a rule** / **Disable a rule**: a rule can now switch another on or off, which is
  what turns single rules into chains. The change applies from the next trigger, not the
  current pass.
- Context conditions that read Android rather than the car: **Wi-Fi network**, **media
  playing**, **call in progress**, **drive duration**, **random chance**. A call in progress
  is read from the audio route, so it cannot tell a ringing phone from a call under way.
- **Media control** (play/pause, next, previous) sent as the media key the steering control
  sends, and **Bluetooth** / **Wi-Fi** on-off actions.

### Fixed

- *Charging* was true whenever the vendor status was non-zero, so a completed charge, a
  stopped one and a charging fault all read as charging. Only the states in which current is
  actually flowing count now.

## [2.3.2] - 2026-08-25

### Fixed

- **The `connectedDevice` foreground type is claimed only while Bluetooth access is held.**
  2.3.1 made the `location` type conditional and left this one unconditional, but it rests on
  BLUETOOTH_CONNECT the same way — a runtime permission, and one the boot start reaches before
  anybody has answered it. On a car that has not granted it, the service still died before its
  first rule cycle. Both types are now claimed only when their permission is held; a service
  that holds neither still runs, and still writes the car, because the vehicle permissions
  come from the platform signature and are not part of this question.

## [2.3.1] - 2026-08-25

### Fixed

- **Every rule now survives a car where position was never granted.** Both services declare
  `location` among their foreground types, and since Android 14 the platform checks each
  declared type against the permissions held at that instant — so on a fresh install, or a
  head unit where the driver said no, the vehicle service died in `onCreate` and took all
  rule evaluation with it, position-based or not. The location type is now claimed only while
  the permission behind it is held, and re-claimed the moment it is granted instead of at the
  next boot.
- **"Near a place" conditions answer again.** The service holds a live position subscription
  while it runs: on a head unit with no other GPS client nothing ever fills the system's
  last-known cache, so every position condition read null and reported itself unavailable.
- **A "send a message" action reaches the phone.** Handing a message to the paired phone over
  the Bluetooth message profile counts as sending an SMS, and the Bluetooth stack enforces
  `SEND_SMS` on the call — a permission the app had never declared, so the send was refused on
  every car. It is now declared, asked for while the action is being written, and a refusal
  from the stack is reported with its actual cause rather than as "InvocationTargetException".
- **Notifications and the paired-device list work without a trip through Settings.** Both
  permissions were declared and never asked for, which on Android means not having them: the
  service notification was dropped silently and the editor's Bluetooth list was always empty.
  They are now requested when the app is opened, alongside position.
- **A denied permission says what it cost.** Refusing contacts or message sending in the
  editor used to look exactly like an empty phone book; each refusal now names the feature it
  disables, and the rule can still be written by hand.

## [2.3.0] - 2026-08-23

### Changed

- **The confirmation prompt now waits as long as its action says** — 5 to 60 seconds, set on
  a slider beside the question. A question asked before the doors unlock is answered at once;
  one asked at the end of a drive has to survive the driver looking away. Rules saved before
  the field existed carry no value and take the default, 10 s, down from 30 s. An unanswered
  question still counts as "no", so a rule that asks before it acts stops when nobody
  answers.
- **`INTERNET` is declared on the stable channel too**, so the "call a webhook" action works
  on the channel people actually drive — until now it silently failed outside unstable
  builds. The app opens no connection of its own: a webhook fires when its rule fires, over
  HTTPS only, and the Console share still uploads nothing without the confirmation dialog.

### Added

- **Send a text message**, a new action. The recipient is picked the way a call's is — a
  contact from the phone book shared over Bluetooth PBAP, or a typed number — and the message
  is written in the action. The head unit has no SIM, so the message is handed to the
  Bluetooth Message Access Profile and the *paired phone* sends it: it needs a phone that
  shares its messages over MAP and allows sending. The Diagnostic tab has a *Text messages*
  row naming the phone that would carry it. A failed send is never retried, because a message
  that went out twice is worse than one that did not go out.
- An example rule wiring a long press on the right steering-wheel star button to a GET
  webhook, behind a confirmation. Disabled on import, with a placeholder URL to replace.

## [2.1.4] - 2026-08-22

### Fixed

- Rule exports made before the MG4Tasker to EVTasker rename are importable again. The
  importer accepts the former `mg4tasker-rules` format marker while new exports keep the
  `evtasker-rules` marker.
- Rule exports preserve action time ranges, webhook payloads and contact display names.

### Added

- An import-ready, disabled-by-default example set with useful rules for display brightness,
  morning media volume and an Eco/high-regeneration parked start.

## [2.1.3] - 2026-08-22

### Fixed

- **"Tune and play radio" still started the radio with the switch off.** 2.1.2 stopped
  EVTasker from asking for playback, which was not enough: the head unit's radio service
  requests the audio focus and unmutes the tuner from inside its own `tune` call, so
  changing station starts the radio whether anyone asked for it or not. The action now
  pauses the radio again as soon as it is tuned when the switch is off (EVHardware 1.2.0),
  which is the only thing the vendor service offers.

  One limitation remains, and it is the vendor's: the radio keeps the audio focus while
  silent, so music that was playing before the rule fired does not resume by itself. The
  service exposes no way to hand the focus back for AM/FM.

## [2.1.2] - 2026-08-22

### Fixed

- **"Tune and play radio" started the radio even with the switch turned off.** The switch
  added in 2.1.0 only controlled the wait/play rows the editor appends after the tune
  action; the tune itself went through `SaicRadio.tune`, which called the vendor's
  `srcPlayRadio` on every tune and made the radio the current audio source regardless. A
  rule meant to preset a station while music kept playing took over the speakers instead.
  Tuning and playback are now separate vendor calls, and the action starts playback only
  when the switch is on. Rules saved before 2.1.0, which carry no wait/play rows, keep
  playing as they always did.

## [2.1.1] - 2026-08-21

### Fixed

- **The outside temperature was always 0 °C.** `OUTSIDE_TEMP` was read only from the AAOS
  `ENV_OUTSIDE_TEMPERATURE` property, which on SWI68 answers `0.0` rather than failing. The
  reading was therefore present and wrong, so the usual protection — a value the car cannot
  report stays out of the snapshot and its conditions never match — did not apply: a rule of
  *outside temperature below 10 °C* fired on a 25 °C afternoon. The value now comes from the
  vendor climate service, the one the car's own HVAC screen reads, and falls back to the
  AAOS property only where that service does not answer. Rules that had been firing year
  round should be checked once after updating.

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
