# MG4Tasker

[![Tests](../../actions/workflows/tests.yml/badge.svg)](../../actions/workflows/tests.yml)
[![Security](../../actions/workflows/security.yml/badge.svg)](../../actions/workflows/security.yml)
[![Release](../../actions/workflows/release.yml/badge.svg)](../../actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> ⚠️ **This app changes vehicle settings automatically and runs on a car.** Read
> [DISCLAIMER.md](DISCLAIMER.md) before installing. A rule is you delegating a setting
> change to happen without anyone touching the screen — write rules accordingly.

Rule-based automation for the MG4: **when** conditions are met at vehicle start, **then**
apply settings.

> If my partner's phone is connected, apply the "Comfort" profile and set the volume to 14.
> If it is below 5 °C on a weekday morning, turn on the seat heating.

MG4Tasker never touches the vehicle itself. It decides, then asks
[MG4Control](../MG4Control) to act — MG4Control holds the vehicle privileges and applies
the safety gate.

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [The speed gate](#the-speed-gate)
- [Conditions and actions](#conditions-and-actions)
- [Firmware compatibility](#firmware-compatibility)
- [Climate and windows (read-only for now)](#climate-and-windows-read-only-for-now)
- [Building](#building)
- [Security](#security)

---

## How it works

```
                    ┌──────────────────────────────────────┐
   Ignition ON  ──► │ MG4Control                           │
                    │  • applies its default profile       │
                    │  • ~8 s later: broadcast             │
                    └──────────────┬───────────────────────┘
                                   │ signature permission
                                   ▼
                    ┌──────────────────────────────────────┐
                    │ MG4Tasker                            │
                    │  1. reads a vehicle snapshot         │
                    │  2. evaluates the rules              │
                    │  3. requests the actions             │
                    │  4. logs the outcome                 │
                    └──────────────┬───────────────────────┘
                                   │ ITaskerBridge (4 methods)
                                   ▼
                    ┌──────────────────────────────────────┐
                    │ MG4Control                           │
                    │  VehicleWriteGate → write or refuse  │
                    └──────────────────────────────────────┘
```

The 8-second delay is not cosmetic: MG4Control applies its own profile at start, and its
climate writes poll vehicle state for several seconds. Waking the tasker earlier would
interleave two write sequences.

**Trigger: ignition only.** No periodic polling, no permanent service. The "Test now"
button replays the same path on demand, and a master switch on the Rules screen disables
automation without deleting rules.

### The IPC contract

Four methods, not eighty. Adding an action to the catalogue does not change the interface,
only the internal `when` on the MG4Control side.

| Method | Role |
|---|---|
| `readSnapshot()` | Full vehicle state in one call |
| `listProfiles()` | MG4Control profiles (id → name) |
| `applyProfile(id)` | Applies a whole profile |
| `applyAction(type, params)` | One action from the closed catalogue |

There is **no** "write property 0xNNNN" method. A compromised caller can only ask for what
the user could already do from MG4Control's own UI.

---

## Requirements

- A working **MG4Control** install.
- Both APKs **signed with the same platform key**. The permission protecting the bridge is
  `protectionLevel="signature"`: signed otherwise, MG4Tasker installs and stays inert. The
  Diagnostic tab says so explicitly.

MG4Tasker does **not** declare `sharedUserId="android.uid.system"` and requests no
`android.car.*` permission. It does not need them — that is the point of the design.

---

## The speed gate

MG4Control refuses any write that changes road behaviour while the car is moving, or when
its speed is unreadable (*fail closed*). MG4Tasker inherits that and does not try to bypass
it.

| | Speed-gated |
|---|---|
| Drive mode, regeneration, one-pedal, ADAS, AEB, ELK, ACC/TJA, limiter, whole profile | **Yes** — when stopped only |
| Seat and steering heating, volume, brightness, audio, notifications | No |

The editor shows "When stopped only" **at the moment you pick the action**, not after. When
an action is refused, the history gives the exact reason instead of staying silent.

`VEHICLE_POWER_OFF` is **deliberately absent** from the catalogue, and a unit test enforces
that no catalogue entry can reach it. Cutting the vehicle stays an explicit human gesture.

---

## Conditions and actions

An unreadable condition makes the rule **not evaluable** — it does not fire, and the
history names the missing signal. Unreadable is never treated as false.

Conditions span **context** (Bluetooth, time of day, day of week, firmware), **environment**
(outside temperature), **driving** (ignition, park, speed, drive mode, regeneration, energy
saving), **climate** (A/C, AUTO, recirculation, fan speed, set temperature, window open),
**comfort** (seat/steering heating, media volume, brightness), and **driver assistance**
(AEB, ELK, ACC/TJA, limiter, TSR, overspeed, speed-limit tone, ADAS sound).

Actions cover **profile** application, **driving**, **comfort**, **audio**, **driver
assistance**, and **system** (launch an app, show a notification). ADAS state — AEB, ELK,
ACC/TJA, TSR, overspeed and so on — is fully covered as gated actions.

The exact per-entry list and its firmware support is generated, not hand-written — see
below.

---

## Firmware compatibility

Every vehicle catalogue entry carries a `@SupportedOn(...)` annotation naming the firmware
generations it works on, derived from MG4Control's `FirmwareInfo` and `MG4Hardware`
routing. That annotation is the single source of truth for three things:

1. **Self-documenting source** — the support set sits next to the entry.
2. **The compatibility matrix** — [docs/firmware-matrix.md](docs/firmware-matrix.md) is
   *generated* from the annotations by `FirmwareMatrix`, checked by a unit test. It is
   never edited by hand.
3. **The runtime filter** — the editor offers only the entries supported on the connected
   car's firmware (reported by MG4Control). One APK adapts to the car; there is no separate
   per-firmware build.

> The matrix is derived from MG4Control's code, **not** from on-vehicle testing. The app's
> Diagnostic tab is the source of truth for your own car: it reads each signal and shows
> "unreadable" where the firmware does not expose it.

Highlights (see the generated matrix for the full grid):

- **Seat / steering heating** — SWI133, SWI68, SWI165 only (the SWI69/131 trims lack the
  hardware).
- **ACC/TJA and speed limiter** — every generation except SWI133.
- **Overspeed alarm / speed-limit tone** — SWI133 and SWI132 only.
- **ADAS sound warning** — every generation except SWI133.
- **Lane-departure sound + vibration** — SWI132 only.
- **Fine audio** (Bose, balance, fader, tone, 3D, speed volume) — SWI133 and SWI132.

---

## Climate and windows (read-only for now)

Air conditioning (A/C, AUTO, recirculation, fan speed, set temperature) and window state
are available as **conditions** — read-only. They use standard AOSP HVAC/window property
ids that the R69 OEM sources expose, but which are **not yet verified on any MG4
generation**. MG4Control reads them null-safely; the Diagnostic tab shows whether each one
is actually readable on your car.

Climate/window **writes** are intentionally not in the catalogue. Adding them means a
verified property map first — writing a wrong id to the vehicle is exactly the risk being
deferred. Confirm the reads on the Diagnostic tab, and the write actions can follow.

---

## Building

```bash
mise install        # JDK 17 (required by AGP 8.5.2)
mise run test       # JVM unit tests (also regenerates docs/firmware-matrix.md)
mise run build      # debug APK
mise run check      # what CI runs
mise run release    # release APK (R8 on)
```

The Android SDK is shared with MG4Control: `mise run bootstrap` lives there.

To sign locally, in `gradle.properties` (never committed) or as environment variables:

```
mg4.keystore=/path/to/platform.keystore
mg4.keystore.password=…
mg4.key.alias=platform
mg4.key.password=…
```

### Layout

```
app/src/main/java/com/mg4/tasker/
  bridge/    bridge client, shared contract, action executor
  model/     Rule, Condition, Action, catalogues, @SupportedOn, FirmwareMatrix
  engine/    condition and rule evaluation — no Android dependency
  store/     JSON persistence of rules and history
  ui/        list, generic editor, history, diagnostic, console
  service/   run cycle (foreground service)
  receiver/  ignition wake-up
  debug/     AppLogger ring buffer + CrashLogger
```

The engine (`engine/`) imports nothing from Android: all decision behaviour — including
refusals and missing data — is testable on the JVM, with no vehicle.

### Adding a condition or action

One enum line in `ConditionType` or `ActionType`, one string, and — for a vehicle entry —
one `@SupportedOn(...)`. The editor builds itself from the `ValueSpec`; no screen to write.
For a vehicle action, add the matching branch to `TaskerBridgeService.dispatch()` in
MG4Control. The firmware matrix regenerates itself on the next test run.

---

## Security

See [SECURITY.md](SECURITY.md) for reporting and scope. Three structural decisions:

1. **MG4Tasker never writes to the vehicle.** One process (MG4Control) talks to the car, so
   there are no concurrent writes and one place applies the speed gate.
2. **The action catalogue is closed.** No arbitrary property write crosses the IPC contract.
3. **The signature permission is the boundary.** The exported bridge service and the
   ignition receiver both require `com.mg4.control.permission.TASKER_BRIDGE`, at
   `protectionLevel="signature"`.

See also [DISCLAIMER.md](DISCLAIMER.md) — this software runs on a vehicle.

## License

MIT — see [LICENSE](LICENSE) and [LICENSE.md](LICENSE.md).
