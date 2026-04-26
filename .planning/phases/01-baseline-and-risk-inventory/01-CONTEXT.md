# Phase 1: Baseline and Risk Inventory - Context

**Gathered:** 2026-04-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 1 establishes the current health of KEPPLR and converts the highest-priority concerns from `.planning/codebase/CONCERNS.md` into a traceable stabilization inventory. It does not fix the concerns yet, refactor large classes, or begin v0.3 feature implementation. The phase should produce evidence, ranking, traceability, and planning inputs that make later stabilization phases concrete.

</domain>

<decisions>
## Implementation Decisions

### Baseline Gates
- **D-01:** Use a practical baseline: run and report `mvn test` and `mvn spotless:check`.
- **D-02:** If either baseline command fails before code changes, treat the failure as known baseline debt and document exact failing tests/checks. Do not hide failures behind a high-level note.
- **D-03:** Do not require live render execution in Phase 1. Inspect the configured render flow and document the gap instead; render-path execution belongs to Phase 3 unless Phase 1 discovers a blocker.
- **D-04:** Capture baseline decisions in this context and require Phase 1 implementation to produce an explicit checklist of baseline commands, results, failures, and gaps.

### Concern Triage
- **D-05:** Rank only high-priority concerns and test gaps, not every item from the concerns audit.
- **D-06:** Use a hybrid priority model: risk first, then v0.3 dependency as the tie-breaker. Regression, security/trusted-code, data-loss, process-exit, and test-blind-spot risks should outrank convenience issues.
- **D-07:** Group the inventory by subsystem and label each concern with severity. Expected subsystem buckets include configuration/reload, scripting, rendering, data/catalogs, Python/model tooling, and test infrastructure.
- **D-08:** Defer items that are new capabilities or major architectural rewrites unless they directly block Phase 1-6 requirements.

### Traceability Format
- **D-09:** Phase 1 implementation must produce both a requirements-to-concerns matrix and a prioritized task list.
- **D-10:** The trace matrix should include: concern, evidence path, requirement ID, subsystem, severity, v0.3 dependency, and recommended disposition.
- **D-11:** Use these disposition labels: `Must fix`, `Should fix`, `Can defer`, and `Out of scope`.
- **D-12:** Store the traceability work as a dedicated markdown artifact under `.planning/phases/01-baseline-and-risk-inventory/` during implementation, and summarize it in the phase summary later.

### Touchpoint Policy
- **D-13:** Identify package-level areas plus representative files rather than exhaustive exact-file fix lists.
- **D-14:** Include touchpoint confidence (`High`, `Medium`, `Low`) based on codebase-map evidence.
- **D-15:** Explicitly list "do not touch yet" boundaries for deferred risky modules/features and out-of-scope v0.3 features.
- **D-16:** For large classes, identify candidate seams but do not implement refactors in Phase 1. Refactors belong in later phases after the inventory ranks them.

### the agent's Discretion
- The planner may choose the exact filename for the dedicated Phase 1 inventory artifact, as long as it lives under `.planning/phases/01-baseline-and-risk-inventory/` and is referenced by the phase summary.
- The planner may decide whether the baseline checklist lives inside the dedicated inventory artifact or in a companion checklist file.
- The planner may choose the final ordering of subsystem buckets if it preserves the selected severity and traceability fields.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project and Phase Scope
- `.planning/PROJECT.md` — Brownfield project context, core value, constraints, and stabilization-first decision.
- `.planning/REQUIREMENTS.md` — Phase 1 requirement IDs `BASE-01`, `BASE-02`, and `BASE-03`; later requirements that shape deferral decisions.
- `.planning/ROADMAP.md` — Phase 1 boundary, success criteria, and six-phase stabilization/foundation sequence.
- `.planning/STATE.md` — Current project state and active phase.

### Codebase Map
- `.planning/codebase/CONCERNS.md` — Primary source of high-priority concerns, test gaps, fragile areas, and deferred feature risks.
- `.planning/codebase/TESTING.md` — Existing test commands, Maven Surefire/Failsafe configuration, render-test gap, and test organization.
- `.planning/codebase/CONVENTIONS.md` — Style gates, Maven Spotless usage, error-handling patterns, logging patterns, and test conventions.
- `.planning/codebase/ARCHITECTURE.md` — Command/state/render/threading boundaries and anti-patterns relevant to touchpoint confidence and "do not touch yet" boundaries.
- `.planning/codebase/STRUCTURE.md` — Package layout and representative file locations for subsystem touchpoints.

### Existing Roadmap and Decisions
- `.claude/KEPPLR_Roadmap.md` — Future-development source, completed v0.2 history, v0.3 recommended implementation order, and settled architectural decisions.
- `.claude/DECISIONS.md` — Existing decision log; read before changing or questioning settled architecture.
- `.claude/REDESIGN.md` — Existing redesign context referenced by the project’s own session-start instructions.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Maven commands in `pom.xml`: use the existing Surefire, Failsafe, and Spotless configuration as the source of truth for baseline gates.
- `.planning/codebase/CONCERNS.md`: use as the initial concerns corpus; do not rediscover the whole codebase from scratch.
- `.planning/codebase/TESTING.md`: use for test command syntax and current render-test gap.
- `.planning/codebase/STRUCTURE.md`: use for representative subsystem file paths in the touchpoint inventory.

### Established Patterns
- `SimulationCommands` is the action boundary for UI, input, and scripts; inventory items should flag direct state/render mutation as a risk.
- `SimulationStateFxBridge` centralizes JavaFX dispatch; touchpoints involving UI thread behavior should check this boundary.
- `KEPPLRConfiguration` is a singleton with thread-local ephemeris access; reload and resource-extraction concerns should be tracked together.
- Tests mirror production package layout under `src/test/java/kepplr`; new test-gap tasks should cite the package where coverage belongs.

### Integration Points
- Baseline gates integrate with `pom.xml`, Maven Surefire/Failsafe, Spotless, and the existing Java 21 toolchain.
- Concern inventory integrates with `.planning/REQUIREMENTS.md` through requirement IDs and `.planning/ROADMAP.md` through phase mappings.
- Representative touchpoints should include package-level areas and files such as `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/render/body/EclipseShadowManager.java`, `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`, and `src/main/python/apps/convert_to_normalized_glb.py` where supported by the concerns audit.

</code_context>

<specifics>
## Specific Ideas

- Phase 1 should create a dedicated markdown inventory artifact with a full trace matrix and prioritized task list.
- The trace matrix should use requirement IDs and concern evidence paths so later phases can plan from it directly.
- Render-path execution is deliberately not part of Phase 1; Phase 1 documents the configured render gap and likely Phase 3 work.

</specifics>

<deferred>
## Deferred Ideas

- Running live render tests is deferred to Phase 3 unless Phase 1 discovers it is required to establish baseline status.
- Refactoring large classes is deferred; Phase 1 only identifies candidate seams.
- v0.3 features such as object search, shot/keyframe system, time slider, scene files, geometry readouts, boresight tools, mesh intersections, and reference layers remain outside Phase 1.

</deferred>

---

*Phase: 1-Baseline and Risk Inventory*
*Context gathered: 2026-04-26*
