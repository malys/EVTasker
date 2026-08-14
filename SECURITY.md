# Security policy

This app decides when settings get written to a car. It holds system-level vehicle access,
so security reports are welcome and taken seriously.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability. Use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead.

Include what you were able to do, on which firmware generation, and whether the vehicle was
moving. A proof of concept helps; a working exploit is not required.

## What is in scope

- Anything that lets another app on the head unit **bind the EVProfile bridge** or forge
  the ignition broadcast without holding the signature permission.
- Anything that makes a rule fire when its conditions are **not** met, or when a condition
  could not be read. Unreadable must never behave like true.
- Anything that gets a vehicle write past `VehicleWriteGate` — for example a bridge action
  reaching a road-behaviour setting through an ungated code path.
- Anything that lets a rule reach a vehicle property **not** in the action catalogue.
- Privilege escalation via the rule store: a crafted rules JSON that causes EVTasker to
  do something outside the catalogue.

## What is not in scope

- Requiring physical access to an unlocked head unit with developer mode enabled.
- The optional EVProfile profile action being absent when EVProfile is unavailable or
  signed with a different key. All other typed actions use EVHardware directly.
- A rule being refused because the car was moving. That is the safety gate working.
- Vulnerabilities in the OEM firmware itself, or in EVProfile's own vehicle layer. Report
  the latter to [EVProfile](../EVProfile).

## Design decisions you should know about

- **EVTasker writes through EVHardware directly.** It carries `android.uid.system`, the
  platform-signature vehicle permissions and the same low-level gate as EVProfile.
  EVProfile is optional and is used only for profile discovery/application.
- **The profile IPC surface is typed.** It takes no raw property id, and requires the
  signature permission shared by the two applications.
- **`VEHICLE_POWER_OFF` is deliberately absent** from the action catalogue, and a unit test
  enforces that no catalogue entry can reach it. Cutting the vehicle stays a human gesture.
- **The signature permission is the whole boundary.** Both the exported bridge service (in
  EVProfile) and the ignition receiver (here) require
  `com.evsuite.profile.permission.TASKER_BRIDGE`, at `protectionLevel="signature"`. Only an APK
  signed with the ROM platform key obtains it.
- **Missing data fails closed.** A condition whose value could not be read makes the rule
  *not evaluable*: it does not fire, and the history names the missing signal. This is
  covered by unit tests, because the alternative — treating unreadable as `0` or `false` —
  would write to a car on an assumption.
- **The ignition trigger is delayed by ~8 s** so EVProfile's own profile application
  finishes first. Interleaving two write sequences was the failure mode being avoided.
- **The standstill gate is fixed and fails closed.** There is no threshold control and no
  park rescue: a valid immediate speed read must be exactly 0 km/h. Automation defaults off
  and requires acceptance of the current versioned warning.
- **A gate refusal is final.** The runtime does not queue the write for a later red light.
- **Position never leaves the car.** The "near a place" condition reads the last known fix
  (no live request), compares it with a point stored in the rule, and yields a yes/no. The
  coordinates are not sent anywhere, including in a shared paste's rule dump, which stores
  the point the user chose — the same one they wrote.
- **Network capability is unstable-only and user-triggered during the audit.** `INTERNET`
  exists only in the unstable manifest. *Share* on the
  Console tab uploads the diagnostic report to a PrivateBin paste
  (`https://paste.chapril.org`). PrivateBin is zero-knowledge — the report is encrypted and
  the key never leaves the device, travelling only in the URL fragment — the paste is
  password-protected and expires after one hour, and nothing is sent unless the user
  confirms the dialog. The unstable OTA hook is suspended during the suite safety and
  legal audit; stable contains no updater implementation or network permission.
