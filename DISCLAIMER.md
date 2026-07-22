# Disclaimer — no warranty, no liability

**Use this software entirely at your own risk.**

This project is provided **"as is"**, without warranty of any kind, express or implied,
including but not limited to the warranties of merchantability, fitness for a particular
purpose and non-infringement. In no event shall the authors or contributors be liable for
any claim, damages or other liability, whether in an action of contract, tort or otherwise,
arising from, out of or in connection with the software or its use.

## What that means concretely

- The app runs on a **vehicle**, and its entire purpose is to **change vehicle settings
  automatically**. Installing it is your decision and your responsibility, including any
  effect on the head unit's stability, your warranty, your insurance, or your vehicle's
  roadworthiness.
- **A rule is you, delegating.** A rule that switches the drive mode, the regeneration
  level, the AEB or the lane-keeping assist changes how the car behaves — without anyone
  touching the screen at that moment. Write rules you would be comfortable having applied
  while you are not paying attention, because that is exactly what happens.
- **Do not configure rules while driving.** Do it parked. Nothing in this app needs
  attention on the move.
- The **safety gate is not a substitute for judgement**. MG4Control refuses road-behaviour
  writes above 0 km/h, and MG4Tasker respects that. It does not make an ill-considered rule
  safe — it only prevents it from landing mid-drive.
- **Compatibility is inferred, not tested.** The firmware table in the README is derived by
  reading MG4Control's code, not by running on cars. Use the app's Diagnostic tab for what
  your own vehicle actually reports.
- Reading and writing vehicle settings uses **undocumented OEM interfaces** discovered by
  inspection. They can change or disappear with any firmware update.
- Release builds are **minified with R8**, and the IPC bridge is resolved by name at bind
  time. Verify a release build on the vehicle before relying on it.

## Not affiliated

This project is **not affiliated with, endorsed by, or supported by** SAIC Motor, MG Motor,
or Google. All trademarks belong to their respective owners. "MG4" is used only to identify
the vehicle the software targets.

## Contributors

Contributors provide their work on the same terms: no warranty, and no liability for how
anyone uses it.
