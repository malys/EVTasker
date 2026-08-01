# Security policy

This app decides when settings get written to a car. It holds no credentials and contacts
no server, but it is the thing that chooses to change your vehicle's behaviour — so
security reports are welcome and taken seriously.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability. Use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead.

Include what you were able to do, on which firmware generation, and whether the vehicle was
moving. A proof of concept helps; a working exploit is not required.

## What is in scope

- Anything that lets another app on the head unit **bind the MG4Control bridge** or forge
  the ignition broadcast without holding the signature permission.
- Anything that makes a rule fire when its conditions are **not** met, or when a condition
  could not be read. Unreadable must never behave like true.
- Anything that gets a vehicle write past `VehicleWriteGate` — for example a bridge action
  reaching a road-behaviour setting through an ungated code path.
- Anything that lets a rule reach a vehicle property **not** in the action catalogue.
- Privilege escalation via the rule store: a crafted rules JSON that causes MG4Tasker to
  do something outside the catalogue.

## What is not in scope

- Requiring physical access to an unlocked head unit with developer mode enabled.
- MG4Tasker doing nothing when MG4Control is absent or signed with a different key. That is
  the design: no signature permission, no bridge, no action. The Diagnostic tab reports it.
- A rule being refused because the car was moving. That is the safety gate working.
- Vulnerabilities in the OEM firmware itself, or in MG4Control's own vehicle layer. Report
  the latter to [MG4Control](../MG4Control).

## Design decisions you should know about

- **MG4Tasker never writes to the vehicle.** Every write happens inside the MG4Control
  process, which owns `android.uid.system` and applies the speed gate. MG4Tasker declares
  no `sharedUserId` and no `android.car.*` permission at all.
- **The IPC surface is four methods**, and none of them takes a raw property id. Adding an
  action to the catalogue does not widen the contract. A compromised caller can only ask
  for things a user could already do from MG4Control's own UI.
- **`VEHICLE_POWER_OFF` is deliberately absent** from the action catalogue, and a unit test
  enforces that no catalogue entry can reach it. Cutting the vehicle stays a human gesture.
- **The signature permission is the whole boundary.** Both the exported bridge service (in
  MG4Control) and the ignition receiver (here) require
  `com.mg4.control.permission.TASKER_BRIDGE`, at `protectionLevel="signature"`. Only an APK
  signed with the ROM platform key obtains it.
- **Missing data fails closed.** A condition whose value could not be read makes the rule
  *not evaluable*: it does not fire, and the history names the missing signal. This is
  covered by unit tests, because the alternative — treating unreadable as `0` or `false` —
  would write to a car on an assumption.
- **The ignition trigger is delayed by ~8 s** so MG4Control's own profile application
  finishes first. Interleaving two write sequences was the failure mode being avoided.
- **One network use, user-triggered.** `INTERNET` exists for a single path: *Share* on the
  Console tab uploads the diagnostic report to a PrivateBin paste
  (`https://paste.chapril.org`). PrivateBin is zero-knowledge — the report is encrypted and
  the key never leaves the device, travelling only in the URL fragment — the paste is
  password-protected and expires after one hour, and nothing is sent unless the user
  confirms the dialog. The unstable flavor additionally self-updates (see *Channels*);
  stable contains no updater code. There is no background traffic in either.
