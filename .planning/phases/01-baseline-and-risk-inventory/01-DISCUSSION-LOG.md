# Phase 1: Baseline and Risk Inventory - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-26
**Phase:** 1-Baseline and Risk Inventory
**Areas discussed:** Baseline gates, Concern triage, Traceability format, Touchpoint policy

---

## Baseline Gates

| Question | Options Presented | Selected |
|----------|-------------------|----------|
| Test baseline scope | Full baseline: `mvn test`, `mvn spotless:check`, and configured render flow; Practical baseline: `mvn test` and `mvn spotless:check`; Fast baseline: `mvn test` only | Practical baseline |
| Failure handling | Treat as known baseline debt and document exact failures; Stop Phase 1 planning until fixed; Record only high-level note | Treat as known baseline debt |
| Render test stance | Run render Maven flow; Inspect configuration and document the gap; Defer render baseline entirely to Phase 3 | Inspect configuration and document the gap |
| Baseline artifact | Context section only; Implementation checklist only; Both | Both |

**User's choice:** `1B 2A 3B 4C`
**Notes:** Phase 1 should be useful and evidence-based without expanding into render execution work.

---

## Concern Triage

| Question | Options Presented | Selected |
|----------|-------------------|----------|
| Ranking scope | All mapped concerns; Only high-priority concerns and test gaps; Only v0.3 blockers | Only high-priority concerns and test gaps |
| Priority model | Risk-first; Roadmap-first; Hybrid risk-first with v0.3 dependency tie-breaker | Hybrid |
| Output grouping | By subsystem; By severity; Both | Both |
| Deferral rule | New features; Major rewrites; Both unless directly blocking Phase 1-6 requirements | Both unless blocking |

**User's choice:** `1B 2C 3C 4C`
**Notes:** The inventory should be focused enough to plan from, not a full restatement of every mapped concern.

---

## Traceability Format

| Question | Options Presented | Selected |
|----------|-------------------|----------|
| Main Phase 1 output | Requirements-to-concerns matrix; Prioritized task list; Both | Both |
| Matrix columns | Basic concern/source/requirement/priority/disposition; subsystem/evidence/phase/owner notes; full trace with concern, evidence path, requirement ID, subsystem, severity, v0.3 dependency, disposition | Full trace |
| Disposition vocabulary | Address now / Defer / Ignore; Must fix / Should fix / Can defer / Out of scope; Phase 1 inventory / Phase 2-6 candidate / v2 roadmap / Out of scope | Must fix / Should fix / Can defer / Out of scope |
| Artifact location | Phase summary only; Dedicated phase artifact; Both dedicated artifact and summary | Both |

**User's choice:** `1C 2C 3B 4C`
**Notes:** Dedicated artifact should be created during Phase 1 implementation and summarized later.

---

## Touchpoint Policy

| Question | Options Presented | Selected |
|----------|-------------------|----------|
| Touchpoint detail | Exact files/classes; Package-level areas plus representative files; Risk modules only | Package-level areas plus representative files |
| Touchpoint confidence | Always include high/medium/low; Only uncertain touchpoints; No confidence | Always include confidence |
| Implementation boundaries | Explicitly list deferred risky modules/features; Only list out-of-scope roadmap features; Let planning infer boundaries | Explicitly list deferred risky modules/features |
| Refactor stance | Inventory only; Allow small preparatory refactors; Identify candidate seams but do not refactor until later phases | Identify candidate seams only |

**User's choice:** `1B 2A 3A 4C`
**Notes:** Phase 1 should guide later planning without doing refactor design prematurely.

---

## the agent's Discretion

- Exact filename for the dedicated inventory artifact.
- Whether baseline checklist is embedded in the inventory artifact or split into a companion file.
- Ordering of subsystem buckets.

## Deferred Ideas

- Live render test execution.
- Large-class refactoring.
- v0.3 feature implementation.
