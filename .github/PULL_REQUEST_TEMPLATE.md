# Pull Request: EVTasker

## 📝 What and Why

**What changes:**
<!-- List the files changed and the functional changes -->

**Why:**
<!-- Explain the problem this solves and how it solves it. "The diff already says what" — your job is to say WHY. -->

---

## 🔍 Verification

**Testing performed:**
- [ ] `mise run check` passes (permission gate + lint + unit tests)
- [ ] New behavior covered by a unit test
- [ ] Tested on real MG4 — Firmware version: ____________
  - If untested on vehicle, explain why and what risk that carries

**Task Sequencing (if automation logic changed):**
- [ ] Rules execute in correct order when multiple triggers fire
- [ ] Condition gates properly prevent rule execution
- [ ] Actions execute atomically (no interleaving with other rules)
- [ ] No race conditions when two rules fire simultaneously
- [ ] State transitions correctly (e.g., entry/exit geofence)

**Vehicle Safety (if any EVProfile interaction added):**
- [ ] All vehicle writes go through EVProfile (never direct access)
- [ ] Speed gate enforced before any vehicle write (0 km/h only)
- [ ] ADAS/safety-critical actions require explicit user confirmation
- [ ] Unreadable conditions make rule **not evaluable** (fail closed, not default-fire)
- [ ] All new failure modes fail closed
- [ ] Firmware support matrix updated (`docs/firmware-matrix.md`)

---

## 🔐 Security Checklist

**Input Validation:**
- [ ] All rule input validated (rule name, trigger values, condition values)
- [ ] No prompt injection in rule names or configuration strings
- [ ] No task injection (rule syntax strictly parsed, no dynamic code execution)
- [ ] Numeric values checked for bounds (speeds, times, radii)
- [ ] Strings sanitized and length-limited

**Task Execution Integrity:**
- [ ] Rule sequencing cannot be bypassed via malicious input
- [ ] Conditions cannot be bypassed or ignored
- [ ] Actions execute atomically (no partial state changes visible to other rules)
- [ ] State corruption impossible (consistent before/after rule execution)

**Permissions & IPC:**
- [ ] EVTasker holds no vehicle privileges (no `android.car.*`, no `sharedUserId`)
- [ ] EVProfile bridge properly authenticated (calls validated)
- [ ] No new `uses-permission` (or added to allowlist with justification)
- [ ] App does NOT self-grant permissions at runtime

**Offline Requirement:**
- [ ] No network code in `src/main/`
- [ ] All automation runs offline (no remote triggers, no cloud state)

---

## 🤖 Optional: Claude AI Assistance

If you'd like Claude AI to help review this PR, include this checklist:
- [ ] I request automated code review from Claude AI
- [ ] I understand Claude may suggest improvements to clarity, efficiency, or safety
- [ ] I grant permission to use my PR content for training (per GitHub's terms)

**Claude Refinement Prompt** (optional — paste if requesting AI review):
```
Please review this automation PR for:
1. Task sequencing correctness (atomicity, no race conditions)
2. Vehicle safety (EVProfile interaction, speed gate, ADAS handling)
3. Input validation (prompt injection, task injection, bounds)
4. State machine consistency (no corruption)
5. Offline operation verification
6. Security checklist compliance
```

---

## 📋 Notes for Reviewer

<!-- Any context, gotchas, decisions, or known limitations for the reviewer -->

