# Licence

This project is released under the **MIT Licence** — see [`LICENSE`](LICENSE).

## Relationship to EVProfile

EVTasker is a companion to [EVProfile](../EVProfile) and useless without it: it holds no
vehicle privileges and reaches the car only through EVProfile's signature-protected
bridge. The two are signed with the same platform key by necessity, not coincidence.

EVProfile is itself a fork governed by its own licence situation. EVTasker adds no new
dependency on that code — it talks to EVProfile over IPC, it does not embed it — so the
MIT licence here applies to EVTasker's own sources.

## No warranty

MIT disclaims all warranty and liability, and this software runs on a **vehicle** and
changes its settings — see [`DISCLAIMER.md`](DISCLAIMER.md) for what that means concretely.
