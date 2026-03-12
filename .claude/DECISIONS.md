# KEPPLR — Architecture Decision Records

Decisions are recorded here to explain *why* the project is shaped the way it is.
Each entry is short by design. For implementation guidance, see `CLAUDE.md` and `REDESIGN.md`.
For the development sequence, see `KEPPLR_Roadmap.md`.

Cross-references in `REDESIGN.md` use `[D-NNN]` inline backrefs.

---

## D-001: Ephemeris access via singleton, never injected
**Status:** Accepted

**Context:** Early prototype bugs traced to stale or mismatched ephemeris instances being
passed around as constructor/method parameters, causing subtle inconsistencies across
the render and UI layers.

**Decision:** Ephemeris is always acquired at point-of-use via
`KEPPLRConfiguration.getInstance().getEphemeris()`. It is never stored as a field or
passed as a parameter. This is Architecture Rule 3 in `CLAUDE.md`.

**Alternatives considered:** Standard dependency injection — rejected because it creates
too many paths for a stale reference to survive.

**Consequences:** Any class that needs ephemeris data can get it without coordination.
CC sessions are instructed to flag constructor/method signatures that accept ephemeris
as a violation before proceeding.

---

## D-002: One-directional state flow (SimulationState / SimulationCommands split)
**Status:** Accepted

**Context:** The prototype mixed UI event handlers, simulation logic, and state mutation
in ways that made threading bugs nearly impossible to reproduce or fix cleanly.

**Decision:** State flows in one direction only. The simulation core writes to
`SimulationState` (observable properties); the JavaFX UI reads from it via bindings.
User input travels the opposite direction exclusively through `SimulationCommands`.
`Platform.runLater(...)` is permitted only at the designated FX bridge layer, not
scattered through UI code.

**Alternatives considered:** Bidirectional binding / shared mutable state — rejected
outright as the source of the prototype's worst bugs.

**Consequences:** UI classes are views only. This constraint is enforced by Architecture
Rules 1, 2, and 4 in `CLAUDE.md` and is checked at the start of every CC session.

---

## D-003: VectorType as strategy interface, not rigid enum
**Status:** Accepted  
**Roadmap step:** 13

**Context:** Step 13 added vector overlays. A rigid enum of vector types would require a
code change every time a new direction vector (e.g., toward an arbitrary NAIF body) was
needed, and would make it impossible to express parameterized types like
`VectorTypes.towardBody(int naifId)` cleanly.

**Decision:** `VectorType` is a strategy interface. Concrete instances are produced by
factory methods on a `VectorTypes` utility class. This allows arbitrary target-body
direction vectors without modifying the enum or the rendering pipeline.

**Alternatives considered:** Enum with a `towardBody` case carrying a NAIF ID field —
rejected because enums with mutable or parameterized state are awkward in Java and
resist extension.

**Consequences:** Adding a new vector type requires implementing the interface, not
touching existing code. CC should treat any attempt to convert `VectorType` back to an
enum as a regression.

---

## D-004: Star catalog abstracted behind StarCatalog/Star interfaces
**Status:** Accepted  
**Roadmap step:** 14

**Context:** Step 14 added star field rendering using the Yale Bright Star Catalog
(`ybsc5.gz`). Binding the renderer directly to the YBSC format would make it difficult
to swap catalogs or add supplementary sources later.

**Decision:** The renderer talks to `StarCatalog` and `Star` interfaces. The YBSC
reader is one implementation. The catalog source is invisible to the render layer.

**Alternatives considered:** Direct YBSC parsing in the renderer — rejected on
extensibility grounds.

**Consequences:** Swapping or supplementing the star catalog requires a new
implementation, not touching the renderer.

---

## D-005: Body-fixed frame with fallback to inertial; SimulationState reflects actual vs. requested
**Status:** Accepted  
**Roadmap step:** 15

**Context:** Step 15 added body-fixed camera frames using PCK rotation matrices. Not all
bodies have PCK orientation data. The UI needed to reflect whether the requested frame
was actually in use or whether a fallback had occurred.

**Decision:** The camera system attempts the requested frame and falls back to the
inertial frame if PCK data is unavailable. `SimulationState` exposes two distinct
properties: the *requested* frame and the *actual* frame. The UI binds to the actual
frame so the user always sees ground truth. The synodic frame was also completed
alongside this step as the work was naturally coupled.

**Alternatives considered:** Silently displaying inertial while claiming body-fixed —
rejected because it would mislead the user and make debugging harder.

**Consequences:** Any new frame mode must update both the requested and actual frame
properties in `SimulationState`. UI code must never infer the actual frame from the
requested frame.

---

## D-006: Camera menu added in Step 15 rather than deferred
**Status:** Accepted  
**Roadmap step:** 15

**Context:** Body-fixed and synodic frames are not visually verifiable without a way to
switch between them interactively. Deferring the Camera menu to a later step would have
left Step 15 without a usable confirmation path.

**Decision:** The Camera menu was added during Step 15 as a prerequisite for visual
confirmation, even though UI work was not the primary focus of that step.

**Consequences:** Establishes the pattern that visual confirmation prerequisites should
be unblocked within the current step rather than deferred. This was also applied in
Step 12 (temporary `focusBody(499)` call to enable orbital trail verification).

---

## D-007: Saturn rings added as Step 16a; original Step 16 split into 16a and 16b
**Status:** Accepted  
**Roadmap step:** 16

**Context:** Saturn's rings were absent from the original roadmap. Their absence was
identified during review prior to Step 16 planning. Shadow and eclipse geometry
(original Step 16) depends on the ring plane mesh existing as a known input — computing
ring shadows without a ring mesh would require either placeholder geometry or rework
when real geometry was added later.

**Decision:** Step 16 was split. Step 16a adds Saturn's rings (mesh, texture, geometry).
Step 16b adds shadows and eclipses, treating the ring plane from 16a as an input.
Prototype reference files (`SaturnRingsController.java`, `SaturnShadowController.java`
from `KEPPLR-pre`) are available for CC to critically evaluate, not to replicate.

**Alternatives considered:** Defer rings to a cosmetic pass after shadows — rejected
because shadow geometry cannot be correctly computed without a ring mesh to cast against.
Address rings and shadows in a single step — rejected as too large a scope for a single
CC session given the coupling complexity.

**Consequences:** Step 16b implementation will be cleaner and avoid rework. CC sessions
on 16a should not couple ring geometry decisions to shadow requirements; those concerns
belong in 16b.

---

## D-008: Prototype code (KEPPLR-pre) is reference, not specification
**Status:** Accepted

**Context:** A working prototype (`KEPPLR-pre`) exists and contains implementations for
several features being rebuilt in KEPPLR. The prototype was not written under the
current architecture rules and contains the class of bugs these rules were designed to
prevent.

**Decision:** CC sessions are instructed to read prototype code critically during the
pre-implementation review phase — to understand *what* was attempted and *what edge
cases were encountered* — but not to port it directly. The architecture rules in
`CLAUDE.md` take precedence over any pattern found in the prototype.

**Consequences:** CC must complete a pre-implementation review phase (read interfaces
and prototype code, report findings) before writing any code. This is non-negotiable
across all steps.

---

## D-009: CC sessions follow a mandatory pre-implementation review phase
**Status:** Accepted

**Context:** Early CC sessions occasionally produced architecturally inconsistent code
when given implementation tasks without first reviewing the existing interface landscape.
The cost of fixing post-hoc violations outweighed the cost of a short review phase.

**Decision:** Every CC session begins with a pre-implementation review: read the
relevant existing interfaces, read any prototype reference code, and report findings
before writing anything. This gate exists regardless of how well-scoped the task appears.

**Consequences:** Prompts to CC must explicitly include the review phase as a required
first step. CC should not treat the review as optional even if the task seems
straightforward.

---

*Last updated: Step 16 planning*  
*Backfill note: Entries D-001 through D-009 were reconstructed retrospectively.
Dates will be added as future decisions are recorded in real time.*
