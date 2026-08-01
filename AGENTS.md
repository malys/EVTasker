# AGENTS.md — MG4Tasker

Companion app to [MG4Control](../MG4Control). Rule-based automation for the SAIC MG4:
at ignition, evaluate rules and apply vehicle settings — **through MG4Control**, never
directly.

Commit author: malys.training@gmail.com

## The one rule that shapes everything

**MG4Tasker never touches the vehicle.** It reads a snapshot from MG4Control and requests
named actions; MG4Control performs every write and applies the 0 km/h speed gate. So:

- No `sharedUserId`, no `android.car.*` permission. MG4Tasker is an ordinary app.
- One process writes to the car → no concurrent writes, one place enforces the gate.
- The IPC surface is 4 methods (`ITaskerBridge`), and none takes a raw property id.

## Boundary = the signature permission

`com.mg4.control.permission.TASKER_BRIDGE` is declared by MG4Control at
`protectionLevel="signature"`. Both apps must be signed with the **same platform key** or
MG4Tasker installs and does nothing. The Diagnostic tab reports the bridge state.

## Unreadable ≠ false

The whole engine rests on this. A condition whose value is missing from the snapshot is
`UNAVAILABLE`; a rule with an unavailable condition is *not evaluable* and does not fire.
Never fill a missing reading with a default — that writes to a car on an assumption.
Covered by `ConditionEvaluatorTest` and `RuleEngineTest`.

## Firmware compatibility is annotation-driven

Every vehicle `ConditionType` / `ActionType` entry carries `@SupportedOn(...)`, derived
from MG4Control's `FirmwareInfo` (`hasHeatFeatures`, `isVsmBased`, `isNewGenVsm`) and the
per-generation routing in `MG4Hardware`. It is the single source of truth for:

- `docs/firmware-matrix.md` — **generated** by `FirmwareMatrix`, refreshed by the test
  run. Never hand-edit it.
- the editor's runtime filter — entries unsupported on the connected car are hidden; an
  unknown firmware hides nothing.

Adding a vehicle entry without `@SupportedOn` fails `FirmwareSupportTest`.

## Triggers are events already received, not new listeners

`TaskerVehicleService` gets every ignition transition from one MG4Hardware listener. The
switch-off trigger reads the other end of that same stream — no second listener, no bind, no
poll. Keep it that way: before adding a trigger, check whether something already delivers the
event. Repeats are filtered in the service (`lastTrigger`), because the bus re-asserts states
and a rule that locks the doors must not run four times.

`Rule.trigger` is **nullable on purpose**. Gson builds instances without calling the
constructor, so a Kotlin default never applies to a key absent from stored JSON; a non-null
enum would be null at runtime for every pre-existing rule. Read it through `firesOn`, export
the raw field. Same trap as the TypeToken bug — see `ShrinkerSafeGsonTest`.

## The standstill gate is configurable, and still fails closed

`VehicleWriteGate.allowUpToKmh` moves the "moving" boundary from 0 up to 50 km/h; the app
stores the user's choice and pushes it in wherever the vehicle layer is initialised. Two
invariants are not negotiable and are unit-tested: an unreadable or negative speed refuses at
any threshold, and the shared layer clamps the value. Default is 0.

## Vehicle writes go in MG4Control, reads too

MG4Tasker adds no vehicle access code. When a new action needs a new capability, implement
the read/write in MG4Control's `MG4Hardware` (branched per firmware generation — never
assume universal), expose it through `TaskerBridgeService`, then add the catalogue entry
here. Property ids and per-generation routing live in MG4Control; do not duplicate them.

Climate, charging, radio and hands-free calls do **not** go through property ids. They use
the SAIC vendor binder services the car's own apps use (`com.mg4.hardware.saic`), with every
descriptor and transaction code read off the decompiled head-unit APKs in `apks/` — cite the
APK and class when you add one. That is what made climate writes honest: the earlier AOSP
climate ids were standard ids the R69 sources name but no MG4 confirmed, so writing them
would have been a guess.

Window writes remain absent — no vendor service exposes them. The AOSP climate reads stay as
a fallback where the vendor service does not answer.

Whether a car has a given vendor service is a **bind, not a table**: the `@SupportedOn` set
records the generations we have evidence for (SWI68/SWI165, the `apks/` platform), and the
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
