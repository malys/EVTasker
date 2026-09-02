# AGENTS.md — EVTasker

Rule-based automation for the SAIC MG4 and part of **EVSuite**. At ignition it evaluates
rules and applies supported actions directly through the shared EVHardware safety layer.
EVProfile is optional and is used only for the "apply saved profile" action.

The workspace `AGENTS.md` and normative workspace `DESIGN.md` apply; this file contains
only automation-specific additions.

Commit author: malys.training@gmail.com

License exception: EVTasker's own sources use PolyForm Noncommercial 1.0.0, not the
workspace MIT default. EVHardware remains separately licensed.

## The one rule that shapes everything

**Automation never bypasses EVHardware.** EVTasker reads a snapshot and executes named,
typed catalogue actions through EVHardware; it never accepts or sends a raw property ID.
The low-level safety gate applies regardless of which app invokes the library. So:

- Vehicle access code, firmware routing and property/transaction IDs live in EVHardware,
  not in this app.
- Rule actions are serialized; no two automation writes may interleave.
- EVProfile IPC is limited to profile discovery/application and takes no raw property ID.

## EVProfile profile boundary

The EVProfile profile bridge is protected by a signature permission. Both apps must be
signed with the same key for profile discovery/application; all other Tasker actions work
without EVProfile. The Diagnostic tab reports profile-bridge and hardware-layer state
separately.

## Unreadable ≠ false

The whole engine rests on this. A condition whose value is missing from the snapshot is
`UNAVAILABLE`; a rule with an unavailable condition is *not evaluable* and does not fire.
Never fill a missing reading with a default — that writes to a car on an assumption.
Covered by `ConditionEvaluatorTest` and `RuleEngineTest`.

## Firmware compatibility is annotation-driven

Every vehicle `ConditionType` / `ActionType` entry carries `@SupportedOn(...)`, derived
from EVHardware's `FirmwareInfo` and its per-generation routing. It is the single source
of truth for:

- `docs/firmware-matrix.md` — **generated** by `FirmwareMatrix`, refreshed by the test
  run. Never hand-edit it.
- the editor's runtime filter — entries unsupported on the connected car are hidden; an
  unknown firmware hides nothing.

Adding a vehicle entry without `@SupportedOn` fails `FirmwareSupportTest`.

## Triggers are events already received, not new listeners

`TaskerVehicleService` gets every ignition transition from one EVHardware listener. The
switch-off trigger reads the other end of that same stream — no second listener or bind.
Gear callbacks are not portable across the supported firmwares, so the P trigger samples
`EVHardware.isVehicleInPark()` every 500 ms only while ignition is RUN and fires only on a
confirmed non-P → P transition. Its first readable sample is a silent baseline, so service
recreation while already parked never fabricates an event. Physical buttons are conditions
(never a `RuleTrigger`): a rule containing one is
addressed by that event and excluded from vehicle-trigger cycles. EVHardware owns the OEM keycode
catalogue and short/long-press state machine; the app only receives the broadcast and feeds
its payload to that decoder. The receiver requires the
signature sender permission because that action is otherwise forgeable. Long press fires on
the OEM long event; its following release is suppressed instead of also firing short press.
Before adding a trigger, check whether something already delivers the event. Ignition repeats
are filtered in the service (`lastTrigger`), because the bus re-asserts states and a rule that
locks the doors must not run four times.

The manual test addresses only the selected rule (by id). It ignores that rule's trigger,
but must never evaluate the rest of the rule store.

`Rule.trigger` is **nullable on purpose**. Gson builds instances without calling the
constructor, so a Kotlin default never applies to a key absent from stored JSON; a non-null
enum would be null at runtime for every pre-existing rule. Read it through `firesOn`, export
the raw field. Same trap as the TypeToken bug — see `ShrinkerSafeGsonTest`.

## The standstill gate is fixed and fails closed

Vehicle-setting writes are allowed only when a valid speed read is exactly 0 km/h. Unknown,
negative or moving speed refuses the action; park state does not rescue an unreadable speed.
Do not expose a user-configurable moving threshold. Any existing `allowUpToKmh` or park-rescue
path is safety debt to remove, not a pattern to extend.

### The one carve-out: closing glass

The window actions (`SET_WINDOWS` and the four per-window writes) are gated **in the opening
direction only**. Closing a window while moving is allowed.

This is deliberate and is the sole exception. Closing the windows when it starts raining on
the motorway is the case those actions exist for, and a standstill gate would refuse exactly
that — the driver would be left with the rain coming in until the next red light. Opening
glass at speed is a vehicle-behaviour change and takes the gate like any other write.

The exception is carried by `ActionType.gatedWhenOpening`, not by `gated`, because it is a
property of the *value* rather than of the action: `DirectExecutor.opensGlass` compares the
target against the position the car reports. **A position the car will not report gates the
write** — an unknown direction is not a safe one, so the fail-closed rule still holds.

Do not widen this to another family, and do not remove the gate from the opening half. Before
this was written down the whole window family was ungated in both directions; the direction
check is what brought it back under the gate, and it is the narrowest exception that keeps the
rain case working.

## A gate refusal is final

MOVING and UNKNOWN_SPEED refusals are reported and are not queued or retried at a later
standstill. `DeferredWrites` is retained only as disabled legacy code during the audit; do
not reconnect its poller or introduce another delayed-write path.

## Vehicle primitives go in EVHardware

EVTasker adds no low-level vehicle access code. When a new action needs a capability,
implement it in the standalone EVHardware repository (branched per firmware generation),
update the typed catalogue and tests, then update this app's submodule and executor.
Property IDs and per-generation routing never live in EVTasker or EVProfile app code.

Climate, charging, radio and hands-free calls do **not** go through property ids. They use
the SAIC vendor binder services the car's own apps use (`com.evsuite.hardware.saic`). Keep every
descriptor and transaction code documented as a project implementation detail. The earlier AOSP
climate ids were standard ids that no MG4 confirmed, so writing them
would have been a guess.

Window writes remain absent — no vendor service exposes them. The AOSP climate reads stay as
a fallback where the vendor service does not answer.

Whether a car has a given vendor service is a **bind, not a table**: the `@SupportedOn` set
records the generations we have evidence for (SWI68/SWI165, the R69 platform), and the
Diagnostic tab's *Vehicle services* row is what widens it.

## Reference patterns (inherited from EVProfile / EVABRPUploader)

- **Signing**: keystore path + passwords from env vars (CI) or `gradle.properties`
  (local); `signingConfig` created only if the file exists. Never a literal secret in a
  build file.
- **Security CI**: `.github/workflows/security.yml` — blocking permission-drift gate +
  gitleaks, plus SARIF from mobsfscan/semgrep/dependency-check.
- **Crash capture**: `CrashLogger` → `filesDir`, keeps the **head** of an oversized
  report, surfaced on the Console tab.
- **In-app console**: `AppLogger` ring buffer + Console screen, so the car is diagnosable
  without ADB.
- **Atomic file writes**: temp file → rename over target. Never delete-then-write.
- **Channel isolation**: stable has no updater implementation — the updater class is not in
  the APK. The unstable updater implementation remains isolated and tested, but its
  `UpdateHook` is intentionally inert while the suite safety and legal audit is open.
  `INTERNET` is no longer part of that isolation: the webhook action is written on the
  channel people drive, so the permission is declared in the main manifest. Nothing opens a
  connection on its own — a webhook fires when its rule fires, and the Console share uploads
  only after the user confirms the dialog.
- **Language**: English by default (code, comments, commits, docs). User strings in
  `values/` (English) + `values-fr/` (French).

## Testing

Decision logic (`engine/`, `FirmwareSupport`, catalogue consistency) is Android-free and
JVM-tested. A vehicle-affecting change is not done until its refusal path (speed > 0, speed
unknown, unreadable property, missing bridge) is covered. Run `mise run test`.

## Build

`mise run test | build | check | release`. JDK 17 (AGP 8.5.2). The Android SDK is shared
with EVProfile (`mise run bootstrap` lives there).
