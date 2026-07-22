# Licence

This project is released under the **MIT Licence** — see [`LICENSE`](LICENSE).

## Relationship to MG4Control

MG4Tasker is a companion to [MG4Control](../MG4Control) and useless without it: it holds no
vehicle privileges and reaches the car only through MG4Control's signature-protected
bridge. The two are signed with the same platform key by necessity, not coincidence.

MG4Control is itself a fork governed by its own licence situation. MG4Tasker adds no new
dependency on that code — it talks to MG4Control over IPC, it does not embed it — so the
MIT licence here applies to MG4Tasker's own sources.

## No warranty

MIT disclaims all warranty and liability, and this software runs on a **vehicle** and
changes its settings — see [`DISCLAIMER.md`](DISCLAIMER.md) for what that means concretely.
