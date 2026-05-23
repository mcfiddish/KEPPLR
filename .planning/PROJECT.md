# KEPPLR Stabilization and v0.3 Foundations

## What This Is

KEPPLR is a Java desktop 3D solar-system and mission-geometry simulator powered by NASA NAIF SPICE kernels, Picante ephemeris/frame calculations, JMonkeyEngine rendering, JavaFX controls, and Groovy scripting. The current project effort is a brownfield stabilization and continuation cycle: address the risks surfaced by the codebase map, preserve the existing architecture decisions in `.claude/KEPPLR_Roadmap.md`, and prepare the codebase for the proposed v0.3 feature work.

## Core Value

KEPPLR must remain a scientifically credible, scriptable, reproducible SPICE visualization tool while new rendering, scene, and mission-geometry features are added.

## Requirements

### Validated

- ✓ Java 21 Maven desktop application with cross-platform packaging — existing
- ✓ SPICE-backed ephemeris, frame, kernel, spacecraft, and instrument support through Picante and KEPPLR configuration — existing
- ✓ JMonkeyEngine render loop with camera-relative floating origin, multi-frustum rendering, body/spacecraft rendering, shadows, rings, labels, trails, vectors, instrument frustums, footprints, and retained swaths — existing
- ✓ JavaFX control window and command/state boundary through `SimulationCommands`, `SimulationState`, and `SimulationStateFxBridge` — existing
- ✓ Groovy scripting and command recording over the `SimulationCommands` API — existing
- ✓ Screenshot, capture sequence, state-string, camera-command, trail-reference, and camera-frame trail workflows described as complete in `.claude/KEPPLR_Roadmap.md` — existing

### Active

- [ ] Reduce highest-risk technical debt and test gaps from `.planning/codebase/CONCERNS.md`.
- [ ] Harden configuration reload, resource extraction, singleton/thread-local ephemeris behavior, and script lifecycle behavior.
- [ ] Add deterministic replay, render manifest, and performance telemetry foundations before heavier v0.3 rendering features.
- [ ] Prepare scene persistence and object discovery work in the order recommended by `.claude/KEPPLR_Roadmap.md`.
- [ ] Preserve existing architectural constraints: JME thread owns render mutation, JavaFX dispatch stays centralized, UI/scripts route actions through `SimulationCommands`, and ephemeris is acquired at point of use.

### Out of Scope

- Full rewrite of large classes — refactor only where it reduces risk for a concrete phase.
- Server/cloud deployment — KEPPLR remains a local desktop simulation and tooling application.
- Untrusted script sandboxing — v1 documents scripts as trusted code and hardens lifecycle behavior; sandboxing is future work.
- Full v0.3 feature completion in this milestone — scene files, manifests, object discovery, and telemetry foundations come first; advanced shot, mesh, readout, reference-layer, and full LOD work are deferred.
- Re-litigating settled design decisions in `.claude/KEPPLR_Roadmap.md` and `.claude/DECISIONS.md` — use them as constraints unless explicitly changed.

## Context

The repository already contains substantial production code under `src/main/java/kepplr`, tests under `src/test/java/kepplr`, Sphinx docs under `doc`, Python asset tooling under `src/main/python`, and Maven packaging through `pom.xml` and `mkPackage.bash`.

The codebase map identifies strong existing boundaries: `SimulationCommands` is the action boundary, `SimulationState` is the observable state model, `KepplrApp` owns the render loop, `KEPPLRConfiguration` centralizes config and ephemeris access, and render managers update long-lived scene objects in place. The same map also highlights risk: very large orchestration classes, configuration singleton/thread-local coupling, shared temp resource extraction, incomplete render-path coverage, cooperative-only script stop behavior, GLB shadow limitations, Gaia edge cases, wide-cone star lookup, and missing deterministic replay/golden tolerance policy.

`.claude/KEPPLR_Roadmap.md` describes the completed historical roadmap through v0.2 and proposes v0.3 sequencing. The recommended first v0.3 work is performance policy/telemetry, `.kepplrscene` atomic load/apply validation, render manifests, object search/bookmarks, and then shot/keyframe work. This project initialization treats the concerns audit as the immediate stabilization agenda and the roadmap as the forward feature agenda.

## Constraints

- **Runtime**: Java 21+, Maven, JMonkeyEngine, JavaFX, Picante, Jackfruit, Groovy JSR-223.
- **Threading**: JME owns simulation and render mutation; JavaFX receives state through `SimulationStateFxBridge`; scripts run on a daemon script thread and enter through commands, waits, or render fences.
- **Architecture**: UI, input, and scripts must route state-changing behavior through `SimulationCommands`; avoid direct render/state mutation from UI code.
- **Ephemeris**: Do not store `KEPPLREphemeris` across components or threads; acquire from `KEPPLRConfiguration.getInstance().getEphemeris()` at point of use.
- **Units**: Distances are kilometers, velocities are km/s, time is ET/TDB seconds past J2000, and render scene positions are camera-relative floating-origin vectors.
- **Testing**: `mvn test` and `mvn spotless:check` are the current correctness/style gates; render-tagged coverage is configured but underused.
- **Security**: Groovy scripts and external tool paths are trusted local inputs; no remote/untrusted automation should be assumed.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Use the codebase map as the brownfield baseline | It captures current stack, architecture, conventions, testing, and concerns directly from the repo. | — Pending |
| Treat `.claude/KEPPLR_Roadmap.md` as the future-development source | The user explicitly identified it as the roadmap for future work. | — Pending |
| Start with stabilization and foundations before large v0.3 features | The concerns audit shows test/reload/render determinism risks that should be reduced before adding heavier visual systems. | — Pending |
| Use coarse GSD granularity for this milestone | The work naturally groups into broad risk-reduction phases with several focused plans each. | — Pending |
| Skip external project research for initialization | This is a brownfield technical roadmap with existing project docs, and SDK research agents are reported unavailable. | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `$gsd-transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `$gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check -> still the right priority?
3. Audit Out of Scope -> reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-26 after initialization*
