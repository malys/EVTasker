# AGENTS.md — MG4Tasker

Rule-based automation for the SAIC MG4 and part of **MG4Suite**. At ignition it evaluates
rules and applies supported actions directly through the shared MG4Hardware safety layer.
MG4Control is optional and is used only for the "apply saved profile" action.

The workspace `AGENTS.md` and normative workspace `DESIGN.md` apply; this file contains
only automation-specific additions.

Commit author: malys.training@gmail.com

## The one rule that shapes everything

**Automation never bypasses MG4Hardware.** MG4Tasker reads a snapshot and executes named,
typed catalogue actions through MG4Hardware; it never accepts or sends a raw property ID.
The low-level safety gate applies regardless of which app invokes the library. So:

- Vehicle access code, firmware routing and property/transaction IDs live in MG4Hardware,
  not in this app.
- Rule actions are serialized; no two automation writes may interleave.
- MG4Control IPC is limited to profile discovery/application and takes no raw property ID.

## MG4Control profile boundary

The MG4Control profile bridge is protected by a signature permission. Both apps must be
signed with the same key for profile discovery/application; all other Tasker actions work
without MG4Control. The Diagnostic tab reports profile-bridge and hardware-layer state
separately.

## Unreadable ≠ false

The whole engine rests on this. A condition whose value is missing from the snapshot is
`UNAVAILABLE`; a rule with an unavailable condition is *not evaluable* and does not fire.
Never fill a missing reading with a default — that writes to a car on an assumption.
Covered by `ConditionEvaluatorTest` and `RuleEngineTest`.

## Firmware compatibility is annotation-driven

Every vehicle `ConditionType` / `ActionType` entry carries `@SupportedOn(...)`, derived
from MG4Hardware's `FirmwareInfo` and its per-generation routing. It is the single source
of truth for:

- `docs/firmware-matrix.md` — **generated** by `FirmwareMatrix`, refreshed by the test
  run. Never hand-edit it.
- the editor's runtime filter — entries unsupported on the connected car are hidden; an
  unknown firmware hides nothing.

Adding a vehicle entry without `@SupportedOn` fails `FirmwareSupportTest`.

## Triggers are events already received, not new listeners

`TaskerVehicleService` gets every ignition transition from one MG4Hardware listener. The
switch-off trigger reads the other end of that same stream — no second listener, no bind, no
poll. Physical buttons are conditions (never a `RuleTrigger`): a rule containing one is
addressed by that event and excluded from ignition cycles. MG4Hardware owns the OEM keycode
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

## A gate refusal is "not now", so it is kept

`DeferredWrites` holds writes refused for MOVING or UNKNOWN_SPEED and re-applies them at the
next standstill. What keeps it honest: only those two verdicts (an unsupported action will
still be unsupported in ten minutes), a 15-minute expiry, dropped at IGNITION_OFF rather than
applied, one attempt each, and a history entry per drain. The poller is a single thread that
exists only while the queue does — `stopIfEmpty()` clears the flag under the lock `offer`
takes, otherwise an entry can be stranded with no poller running.

## Vehicle primitives go in MG4Hardware

MG4Tasker adds no low-level vehicle access code. When a new action needs a capability,
implement it in the standalone MG4Hardware repository (branched per firmware generation),
update the typed catalogue and tests, then update this app's submodule and executor.
Property IDs and per-generation routing never live in MG4Tasker or MG4Control app code.

Climate, charging, radio and hands-free calls do **not** go through property ids. They use
the SAIC vendor binder services the car's own apps use (`com.mg4.hardware.saic`). Keep every
descriptor and transaction code documented as a project implementation detail. The earlier AOSP
climate ids were standard ids that no MG4 confirmed, so writing them
would have been a guess.

Window writes remain absent — no vendor service exposes them. The AOSP climate reads stay as
a fallback where the vendor service does not answer.

Whether a car has a given vendor service is a **bind, not a table**: the `@SupportedOn` set
records the generations we have evidence for (SWI68/SWI165, the R69 platform), and the
Diagnostic tab's *Vehicle services* row is what widens it.

## Reference patterns (inherited from MG4Control / MG4ABRPUploader)

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
- **Language**: English by default (code, comments, commits, docs). User strings in
  `values/` (English) + `values-fr/` (French).

## Testing

Decision logic (`engine/`, `FirmwareSupport`, catalogue consistency) is Android-free and
JVM-tested. A vehicle-affecting change is not done until its refusal path (speed > 0, speed
unknown, unreadable property, missing bridge) is covered. Run `mise run test`.

## Build

`mise run test | build | check | release`. JDK 17 (AGP 8.5.2). The Android SDK is shared
with MG4Control (`mise run bootstrap` lives there).
