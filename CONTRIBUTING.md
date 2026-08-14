# Contributing to EVTasker

Thank you for contributing! This guide keeps your work aligned with the project's automation safety, security, and quality requirements.

## 🚨 Critical Context: Automated Vehicle Actions

**EVTasker runs unattended automation sequences on your car.** Incorrect rule execution, gating, or sequencing can:
- Execute unintended vehicle actions
- Corrupt automation state
- Create a security vulnerability where malicious inputs trigger bad behavior
- Block critical user actions incorrectly

**Every PR touching rule sequencing, condition evaluation, or vehicle interaction must include unit tests and a clear verification plan.**

---

## 🎯 Ground Rules

1. **EVTasker writes only through the typed EVHardware catalogue.** Raw property ids and
   independent safety decisions do not belong in this app. EVProfile is optional and is
   used only to discover or apply a saved profile.
2. **Unreadable is not false.** A missing or unreadable condition makes a rule *not evaluable* — it must not fire.
3. **Fail closed.** Unknown firmware, unreadable speed, missing bridge: refuse or omit. Do not guess.
4. **Atomic execution only.** When multiple rules fire together, they execute in order without interleaving. Do not expose partial state to other rules.
5. **Sanitize everything.** Rule names, trigger values, condition values, and action targets are hostile input.
6. **Offline only.** No remote triggers, no cloud state, no network dependencies in `src/main/`.
7. **Be transparent about verification.** "Passes CI, not tested on vehicle" is a valid note. Silence is not.

---

## 🔧 Setup & Testing

### Build & Test Locally

```bash
mise run check      # permission gate + lint + unit tests

# Without mise:
bash .github/security/check-permissions.sh
./gradlew testDebugUnitTest lintDebug
```

### Recommended Testing

- **Unit tests:** rule parsing, input validation, condition evaluation, sequencing, state transitions
- **Emulator tests:** non-vehicle logic, rule editor, rule persistence
- **Vehicle tests:** simultaneous rule firing, condition gating, EVProfile writes, speed gate enforcement, fail-closed behavior

### Task Sequencing Protocol

1. Create two overlapping rules that can fire at the same time.
2. Trigger both simultaneously.
3. Verify execution order is deterministic.
4. Confirm rule execution is atomic and no partial state is visible.

---

## 💻 Coding Standards

### Rule Parsing & Validation

```kotlin
// ✅ GOOD: Strict input validation
fun parseRule(json: String): Result<RuleDefinition> {
    return try {
        val parsed = Json.decodeFromString<RuleDefinition>(json)
        require(parsed.name.length in 1..128)
        require(parsed.triggers.isNotEmpty())
        Result.success(parsed)
    } catch (e: Exception) {
        Result.failure(SecurityException("Invalid rule: ${e.message}"))
    }
}

// ✅ GOOD: Fail-closed on unreadable conditions
fun evaluateConditions(conditions: List<Condition>): EvaluationResult {
    for (cond in conditions) {
        val value = readProperty(cond.property) ?: return EvaluationResult.NOT_EVALUABLE
        if (!cond.matches(value)) return EvaluationResult.FALSE
    }
    return EvaluationResult.TRUE
}
```

### Task Sequencing & Atomicity

```kotlin
fun executeRuleSequence(rules: List<Rule>) {
    synchronized(ruleStateMutex) {
        for (rule in rules) {
            if (shouldExecute(rule)) {
                executeAction(rule.action)
                updateState(rule)
            }
        }
    }
}
```

### Input Sanitization

```kotlin
fun setRuleName(ruleName: String): Boolean {
    val sanitized = ruleName
        .take(128)
        .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }
    if (sanitized.isEmpty()) return false
    preferences.edit().putString("rule_name", sanitized).apply()
    return true
}
```

### Vehicle Safety

- No direct vehicle writes in EVTasker
- Speed gate enforced before any vehicle action
- ADAS actions require explicit approval
- Fail-closed on missing or stale data

---

## 📋 Submitting a Pull Request

1. Run `mise run check`
2. Create a feature branch
3. Open PR using `.github/PULL_REQUEST_TEMPLATE.md`
4. Address review feedback
5. Merge when approved

> Use the PR template to document verification, sequencing tests, EVProfile interaction, and security impact.

---

## 🔐 Security Guidelines

### Input Validation

All input must be:
- Type-checked
- Bounds-checked
- Length-limited
- Sanitized
- Tested against malicious values

### Task Execution Integrity

- No prompt injection in rule names or fields
- No task injection via rule syntax
- No condition bypass
- Atomic execution only
- State consistency enforced

### Permissions

Any new `uses-permission` fails CI until added to `.github/security/permission-allowlist.txt` with justification.

### Offline Requirement

- No network code in `src/main/`
- All automation runs locally
- No remote triggers or cloud state

---

## 📚 Project Structure

```
EVTasker/
├── app/src/main/          # Automation engine and rule runtime
├── app/src/test/          # Unit tests for rules, sequencing, validation
├── .github/               # Issue templates, PR template, security gate
├── docs/                  # Firmware support and rule docs
├── SECURITY.md            # Vulnerability reporting
├── DISCLAIMER.md          # Legal disclaimer
└── CONTRIBUTING.md        # This file
```

---

## 📖 Resources

- **SECURITY.md** — Security policy and reporting
- **DISCLAIMER.md** — Disclaimer and legal status
- **README.md** — Project overview
- **docs/firmware-matrix.md** — Supported firmware details

---

## ❓ Questions?

- General questions: open an issue
- Security issues: see `SECURITY.md`
- Build issues: check the README or open an issue

---

**Thank you for contributing safe automation!** 🎉
