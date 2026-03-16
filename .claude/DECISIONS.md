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

## D-010: Camera transitions are non-blocking; pointAt uses slerp, goTo uses linear interpolation
**Status:** Accepted  
**Roadmap step:** 18

**Context:** Step 18 added `pointAt` and `goTo` as camera transition primitives. Two
interpolation choices were required: one for orientation slews, one for distance
translation.

**Decision:** Both primitives are non-blocking — they return immediately and progress
advances in `simpleUpdate()`. `pointAt` uses spherical linear interpolation (slerp) on
quaternions to produce a smooth, constant-angular-rate slew. `goTo` uses linear
interpolation on camera distance. Easing (acceleration/deceleration) is deferred.

**Alternatives considered:** Blocking transitions — rejected because they would stall
the simulation loop and prevent the Groovy scripting layer from interleaving other
commands. Easing curves — deferred, not rejected; recorded in the roadmap as a known
refinement.

**Consequences:** `waitTransition()` is required in the Groovy scripting layer to
sequence camera moves in scripts. `transitionActiveProperty()` and
`transitionProgressProperty()` are exposed on `SimulationState` so the UI and scripting
layer can observe completion.

---

## D-011: Transition applied before camera frame block in simpleUpdate()
**Status:** Accepted  
**Roadmap step:** 18

**Context:** The `simpleUpdate()` loop contains a camera frame block that rotates the
camera's inertial pose into the active frame (BODY_FIXED, SYNODIC, or INERTIAL). The
question was whether `TransitionController.update()` should run before or after this
block.

**Decision:** Transitions are applied before the camera frame block. The transition
moves the camera's inertial J2000 pose toward its target. The frame block then applies
its rotation on top of that inertial pose. This means a slew targets a fixed inertial
direction and the active frame rotation layers on top consistently each frame.

**Alternatives considered:** Applying transitions after the frame block — rejected
because it would interpolate in the already-rotated frame space, causing a `pointAt`
slew in SYNODIC or BODY_FIXED frame to drift as the frame itself rotates during the
transition.

**Consequences:** The call order in `simpleUpdate()` is fixed as: input handler update
→ manual navigation check → transition update → camera frame block → state update. This
ordering must not be changed without explicit discussion.

---

## D-012: Manual navigation input cancels in-progress transitions immediately
**Status:** Accepted  
**Roadmap step:** 18

**Context:** If the user drags the mouse or scrolls while a `pointAt` or `goTo`
transition is in progress, the transition and the input handler would conflict over the
camera pose.

**Decision:** Manual navigation input cancels the active transition immediately and
returns full control to the input handler. Detection is via a `consumeManualNavigation()`
flag on `CameraInputHandler`, checked each frame before `TransitionController.update()`.

**Alternatives considered:** Blending manual input with transition progress — rejected
as complex and likely to feel unresponsive. Ignoring manual input during transitions —
rejected as it would make the camera feel unresponsive and trap the user.

**Consequences:** `CameraInputHandler` has a small addition: a
`manualNavigationThisFrame` boolean field and a `consumeManualNavigation()` method.
This is the only class outside `TransitionController` that participates in transition
cancellation.

---

## D-013: DEFAULT_GOTO_APPARENT_RADIUS_DEG = 10.0 (visually tuned)
**Status:** Accepted  
**Roadmap step:** 18

**Context:** `goTo` needs a default apparent radius to use when `focusBody` drives the
transition. The formula `endDistance = bodyRadius / tan(apparentRadiusDeg × π/180)`
means the choice directly controls how close the camera ends up relative to the target
body.

**Decision:** 10.0 degrees was chosen as the default. At this value Earth ends up at
approximately 5.7× its radius — a dramatic close-up that clearly confirms the transition
worked. This value is visually tuned, not physically derived. It is defined as a named
constant in `KepplrConstants` and can be adjusted without touching logic code.

**Alternatives considered:** 1.0° (too small — body fills only ~4% of screen height at
typical FOV), 5.0° (reasonable but less dramatic). 10.0° selected as the most
immediately satisfying default for interactive use.

**Consequences:** Scripts that call `focusBody` will land at 10° apparent radius by
default. Scripts requiring a specific distance should call `goTo` directly with an
explicit value.

---

## D-014: focusBody and targetBody compose pointAt/goTo internally; signatures unchanged
**Status:** Accepted  
**Roadmap step:** 18

**Context:** Step 18 introduced `pointAt` and `goTo` as explicit primitives on
`SimulationCommands`. The existing `focusBody` and `targetBody` methods had direct
camera manipulation semantics that needed to be expressed through these new primitives.

**Decision:** `targetBody` calls `pointAt` internally after updating targeted body
state. `focusBody` calls `pointAt` then queues `goTo` as a pending transition that
begins when `pointAt` completes. Neither method's signature changes. Existing call
sites require no updates.

**Alternatives considered:** Removing `focusBody`/`targetBody` and requiring callers to
compose `pointAt`/`goTo` manually — rejected because it would break existing call sites
and the Groovy scripting API, and because the composed semantics are the correct default
behavior that all callers want.

**Consequences:** `TransitionController` supports a pending transition queue of depth 1
to handle the `pointAt`-then-`goTo` sequence initiated by `focusBody`. A second
`pointAt` or `goTo` issued while one is active cancels the active transition and
replaces the pending one.

---

## D-015: waitTransition() placed in com.kepplr.scripting at Step 18
**Status:** Accepted  
**Roadmap step:** 18

**Context:** `waitTransition()` is a blocking primitive that returns when
`transitionActiveProperty()` becomes false. It is needed by the Groovy scripting layer
(step 20) to sequence camera moves in scripts. The question was whether to defer it
entirely to step 20 or implement it alongside the property it depends on.

**Decision:** `waitTransition()` is implemented in `com.kepplr.scripting` at step 18,
alongside `transitionActiveProperty()`. It is not yet exposed through the Groovy
wrapper — that happens in step 20 — but the method exists and is callable. This keeps
related things together and ensures step 20 has no loose ends to pick up from step 18.

**Alternatives considered:** Deferring entirely to step 20 — rejected because it would
leave `transitionActiveProperty()` without a consumer and make step 20 responsible for
logic that is naturally part of the transition system.

**Consequences:** Step 20 exposes `waitTransition()` through the Groovy wrapper without
any implementation work. The method must not be moved or renamed between steps 18
and 20.

---

## D-016: Two-window layout; transparent overlay rejected
**Status:** Accepted
**Roadmap step:** 19

**Context:** Step 19 required a JavaFX control panel alongside the JME render window.
A transparent overlay stage (`StageStyle.TRANSPARENT`, `setAlwaysOnTop(true)`) positioned
over the JME window was prototyped in a dedicated spike project (`KEPPLR-overlay-spike`)
and confirmed working on macOS and Linux (XWayland). However, position-sync lag on macOS
was unacceptable in practice — GLFW position callbacks fire after the window has already
moved, producing a visible rubber-band effect.

**Decision:** The JavaFX control panel is a separate OS window (two-window layout). No
`WindowManager` class. No GLFW position/minimize/focus callbacks. No Linux X11 forcing
workaround. The JavaFX stage is a normal, non-transparent, non-always-on-top window
positioned to the right of the JME window at launch.

**Alternatives considered:** Transparent overlay (Option B) — prototyped and rejected
due to macOS lag. Embedding JavaFX in the JME canvas — rejected on complexity grounds
before the spike.

**Consequences:** Both windows close regardless of which one the user closes — symmetric
shutdown via `destroy()`. The spike project (`../KEPPLR-overlay-spike`) is retained as
reference but its workarounds are not applied. CC must not revisit this decision.

---

## D-017: Tracking is camera frame selection, not a distinct interaction mode
**Status:** Accepted
**Roadmap step:** 19

**Context:** The original design (REDESIGN.md §4.6) defined "tracked" as a fourth
interaction mode with a screen-space anchor that locked a body to a fixed screen position.
During step 19 implementation it became clear that this behavior is equivalent to the
Synodic camera frame with the targeted body as the "other body" — which was already
implemented. Maintaining a separate tracking concept would have duplicated behavior and
added dead state properties.

**Decision:** Tracking is removed as a distinct mode. The F key toggles the camera frame
between SYNODIC and INERTIAL. Stop Tracking in the View menu switches to INERTIAL. Both
are kept in sync with the Camera Frame submenu radio items. `trackedBodyId`,
`trackingAnchor`, `trackBody()`, and `stopTracking()` do not exist in the codebase.

**Alternatives considered:** Implementing screen-position locking as a distinct behavior
from the Synodic frame — rejected because the user-visible result is identical for the
primary use case (focus Earth, target Moon, keep Moon in view), and maintaining two
parallel mechanisms for the same behavior creates confusion and dead code paths.

**Consequences:** `SimulationState` has no tracked body property. `SimulationCommands`
has no `trackBody()` or `stopTracking()`. The F key is handled in `CameraInputHandler`
as a `setCameraFrame()` toggle. Any future requirement for true screen-position locking
(independent of the Synodic frame) should be recorded as a new decision before
implementing.

---

## D-018: Mouse picking is screen-space only; no 3D ray cast
**Status:** Accepted
**Roadmap step:** 19

**Context:** Step 19 required click-to-select body picking in the JME render window.
An initial implementation used a 3D ray cast from the camera through the click position.
This failed for nearby large bodies (e.g., the Sun at close range) because body node
positions in the scene graph are camera-relative (floating origin) — a ray built in
heliocentric world space misses nearby bodies whose scene-graph position is near the
origin regardless of their true distance.

**Decision:** Picking is entirely screen-space. Every visible body is projected to screen
coordinates and its screen-space radius is computed. A click selects the candidate within
`PICK_MIN_SCREEN_RADIUS_PX` of the click point with the largest actual screen radius.
If no candidates exist, the click is ignored. No 3D ray cast anywhere in the picking
path.

**Alternatives considered:** Fixing the ray cast to use camera-relative space — rejected
because the screen-space approach is simpler, handles the minimum pick radius requirement
naturally, and resolves the overlapping-bodies case (largest body wins) without additional
logic.

**Consequences:** `PICK_MIN_SCREEN_RADIUS_PX` is defined in `KepplrConstants`. The pick
algorithm runs in `CameraInputHandler` against projected screen positions maintained by
the render layer. CC must not reintroduce a ray cast without explicit discussion.

---

## D-019: Barycenter trails suppressed in GUI except Pluto Barycenter
**Status:** Accepted
**Roadmap step:** 19b

**Context:** The body list includes planet barycenters (e.g., Jupiter
Barycenter, Saturn Barycenter) as top-level entries. For the major planets,
the barycenter is so close to the planet's center of mass that plotting both
the barycenter trail and the planet trail produces a visually identical
duplicate. The Pluto system is different — Charon is massive enough relative
to Pluto that the Pluto-Charon barycenter is measurably offset from Pluto
itself and lies outside Pluto's surface, making the barycenter trail
meaningful and distinct from Pluto's trail.

**Decision:** The GUI global trail toggle does not enable trails for
barycenter bodies (NAIF IDs x99 series and Solar System Barycenter ID 0),
with one exception: the Pluto Barycenter (NAIF ID 9) trail is enabled, and
Pluto (NAIF ID 999) is treated as a satellite of its barycenter in the body
list tree (as it already is in the NAIF hierarchy). The per-body API
(setTrailVisible) imposes no such restriction — a script may enable any
barycenter trail explicitly.

**Alternatives considered:** Suppressing all barycenter trails including
Pluto — rejected because the Pluto Barycenter trail is visually meaningful.
Showing all barycenter trails — rejected as visually redundant for the major
planets and cluttering.

**Consequences:** The global trail toggle in the Overlays menu must
explicitly skip barycenter NAIF IDs when iterating over known bodies, except
for NAIF ID 9. The NAIF ID range for barycenters and the Pluto Barycenter
exception must be defined as named constants in KepplrConstants, not
hardcoded in the menu handler.

---

## D-020: Label and trail decluttering uses screen-space proximity to primary body
**Status:** Accepted
**Roadmap step:** 19b

**Context:** Step 19b added zoom-dependent label and trail visibility. The
requirement was that satellites cluster near their primary at large distances
and become visible as the camera zooms in. Two approaches were considered:
distance-based thresholds (suppress below N km from primary) and screen-space
proximity (suppress when within N pixels of primary on screen).

**Decision:** Decluttering is screen-space only. Each frame, a body's label
(and trail) is suppressed if any body with a larger physical radius has its
screen-space center within `LABEL_DECLUTTER_MIN_SEPARATION_PX` (or
`TRAIL_DECLUTTER_MIN_SEPARATION_PX`) of the candidate body's screen center.
Both thresholds are defined in `KepplrConstants`. The decluttering logic lives
entirely in the rendering layer — not in `SimulationCommands` or `ui/`.

**Alternatives considered:** Distance-based thresholds — rejected because the
relevant perceptual quality is screen separation, not physical distance.
Per-body priority rules — deferred as a future refinement (§14.5).

**Consequences:** The render layer must project all visible bodies to screen
space each frame before evaluating decluttering. This is already done for
picking (D-018), so the projection data can be reused.

---

## D-021: Direction vectors and orbit trails use per-body color from configuration
**Status:** Accepted
**Roadmap step:** 19b

**Context:** Direction vector arrows and orbit trail lines needed a color
source. Options were a fixed default color, a color derived from body type
(planet/spacecraft/asteroid), or a per-body color from the configuration file.

**Decision:** Both direction vectors and orbit trails use the body's color
from the configuration body block. If no color is specified for a body,
a default fallback color defined in `KepplrConstants` is used. Color is
resolved at render time from `KEPPLRConfiguration` — it is not cached in
`SimulationState`.

**Alternatives considered:** Fixed color per overlay type — rejected as
visually undifferentiated when multiple bodies are visible. Body-type-derived
color — rejected as less flexible than explicit configuration.

**Consequences:** The configuration body block must support a color field.
The trail and vector rendering subsystems must query the configuration at
render time. No color state on `SimulationState` or `SimulationCommands`.

---

## D-022: Labels rendered only for sprite-class bodies
**Status:** Accepted
**Roadmap step:** 19b

**Context:** Labels on full-geometry bodies (apparent radius ≥
`DRAW_FULL_MIN_APPARENT_RADIUS_PX`) are redundant — the body is large enough
to be unambiguously identified visually. Labels on large nearby bodies also
clutter the view and overlap the body geometry itself.

**Decision:** A label is never drawn for a body whose apparent screen radius
is ≥ `DRAW_FULL_MIN_APPARENT_RADIUS_PX`. Labels are drawn only for
sprite-class bodies. This threshold is the same constant already used to
determine geometry vs. sprite rendering in step 11. No new constant is needed.

**Alternatives considered:** Always drawing labels and relying on the
decluttering policy to suppress them — rejected because the decluttering
policy is proximity-based and would not suppress a label on a large isolated
body. A separate label-suppression threshold — rejected as unnecessary
duplication of an existing constant.

**Consequences:** The label render pass must check apparent radius before
drawing. The `setLabelVisible` API still controls whether a label is
*eligible* to render — the apparent radius check is an additional suppression
applied on top of it.

---

## D-023: Camera navigation commands added to SimulationCommands for scripting parity
**Status:** Accepted
**Roadmap step:** 19c

**Context:** Step 20 requires every SimulationCommands method to be loggable
as a replayable Groovy script. Camera navigation (zoom, orbit, tilt, roll,
FOV, position, orientation) was implemented directly in CameraInputHandler
without going through SimulationCommands, making it unscriptable. Full mouse-
gesture parity was considered but rejected — replicating drag gestures as
script primitives is unnatural and unhelpful.

**Decision:** A selective set of camera commands is added to SimulationCommands
covering the actions a script author would genuinely want: zoom, orbit, tilt,
roll, setFov, setCameraPosition, setCameraLookDirection, and setSynodicFrame.
All are non-blocking and use the existing TransitionController infrastructure
from step 18. Fine-grained mouse drag navigation remains in CameraInputHandler
and is intentionally unscriptable.

setSynodicFrame(focusNaifId, targetNaifId, durationSeconds) changes only the
camera frame — it does not update focused, targeted, or selected body state in
SimulationState. setCameraFrame(SYNODIC) continues to derive focus and target
from SimulationState for interactive use.

setCameraPosition defaults to the current focus body as origin. The explicit
overload accepts an originNaifId for scripts that need to position the camera
relative to a specific body regardless of current focus state.

**Alternatives considered:** Full mouse-gesture parity — rejected as unnatural
for scripting. Blocking camera commands — rejected for consistency with the
non-blocking transition model established in D-010.

**Consequences:** CameraInputHandler delegates to SimulationCommands for all
actions covered by this step. The Groovy wrapper (step 20) adds name resolution
and default-duration overloads on top of these methods. No camera logic moves
to the scripting layer — the Groovy wrapper calls SimulationCommands only.

---

## D-024: CommandRecorder uses decorator pattern with hybrid coalescing for instant camera commands
**Status:** Accepted
**Roadmap step:** 20

**Context:** Step 20 required a recording feature that captures interactive sessions
as runnable Groovy scripts. Instant camera commands (`durationSeconds == 0`) from
mouse and keyboard navigation fire many times per second — recording them verbatim
would produce hundreds of lines per second of nearly identical incremental calls,
making the output unreadable and difficult to edit.

**Decision:** `CommandRecorder` is a decorator implementing `SimulationCommands` that
wraps the live implementation. Instant same-type camera commands are coalesced within
a 250ms window. A pose snapshot is taken on the first instant command after a flush
to detect gesture boundaries; accumulated deltas are combined using type-appropriate
math (sum degrees for orbit/tilt/roll/yaw; multiply factors for zoom; last-value-wins
for setFov/setCameraPosition/setCameraLookDirection). The coalesced result is flushed
as a single relative command when the window expires, a different command type arrives,
or any command with `durationSeconds > 0` arrives. Commands with `durationSeconds > 0`
are always recorded verbatim. The coalescing window constant
`RECORDER_COALESCE_WINDOW_MS = 250L` is defined in `KepplrConstants`.

**Alternatives considered:** Recording verbatim — rejected as producing unreadable
output. Rate-limiting to one command per second — rejected because gesture end state
might not be captured. Pure pose snapshots (absolute position + orientation) — rejected
because absolute poses are frame-dependent and become incorrect at replay time if the
simulation ET differs from record time; relative commands like `orbit()` and `zoom()`
are ET-agnostic.

**Consequences:** Recorded scripts are readable and editable. The pose snapshot is
internal bookkeeping only and is never emitted to the script. `ScriptRunner` uses raw
`SimulationCommands` directly, not the recorder, so script execution is never
self-recording.

---

## D-025: Scripts run on a dedicated daemon thread; re-run shows confirmation dialog
**Status:** Accepted
**Roadmap step:** 20

**Context:** Groovy scripts call blocking timing primitives (`waitTransition()`,
`waitSim()`, `waitWall()`). If scripts ran on the JME render thread, blocking would
freeze `simpleUpdate()` and prevent transitions from advancing, causing permanent
deadlock.

**Decision:** Each script runs on a dedicated daemon thread named
`kepplr-groovy-script`, separate from both the JME render thread and the JavaFX
application thread. `SimulationCommands` methods are already thread-safe (they post
to `TransitionController`'s `ConcurrentLinkedQueue` inbox), so no additional
synchronization is needed for script calls. If `Run Script` is invoked while a script
is already running, a JavaFX confirmation dialog is shown: "A script is already
running. Stop it and run the new one?" If confirmed, the current script thread is
interrupted and `cancelTransition()` is called before the new script starts. A stopped
script leaves `SimulationCommands` in a consistent state.

**Alternatives considered:** Running scripts on the JME render thread — rejected due
to deadlock risk. Running scripts on the JavaFX thread — rejected for the same reason
and because it would block the UI. Silently replacing the running script without
confirmation — rejected as too destructive without user intent.

**Consequences:** `cancelTransition()` was added to `SimulationCommands` and
implemented through `TransitionController` as a `CancelRequest` in the sealed
`PendingRequest` hierarchy. `waitSim()` and `waitUntilSim()` block indefinitely if
the simulation is paused or the time rate works against the target; this is documented
in their Javadoc.

---

## D-026: VectorType carries a toScript() method for CommandRecorder serialization
**Status:** Accepted
**Roadmap step:** 20

**Context:** `CommandRecorder` serializes every `SimulationCommands` call to a Groovy
script. `setVectorVisible(int naifId, VectorType type, boolean visible)` takes a
`VectorType` strategy interface instance. Because `VectorType` is an interface (D-003),
not an enum, the recorder has no way to introspect which `VectorTypes` factory method
produced a given instance. Without a solution, recorded `setVectorVisible` calls would
emit an unrunnable placeholder comment.

**Decision:** A `toScript()` method is added to the `VectorType` interface. Each
concrete implementation returned by `VectorTypes` factory methods implements it to
return the exact Groovy expression that recreates the instance:
- `VectorTypes.velocity()` → `"VectorTypes.velocity()"`
- `VectorTypes.bodyAxisX/Y/Z()` → `"VectorTypes.bodyAxisX()"` etc.
- `VectorTypes.towardBody(10)` → `"VectorTypes.towardBody(10)"`

`CommandRecorder` calls `vectorType.toScript()` when building the recorded line.

**Alternatives considered:** Reflection-based introspection — rejected as brittle and
dependent on implementation details. An enum-based registry mapping instances back to
factory calls — rejected as incompatible with the strategy interface design and the
parameterized `towardBody` case. Omitting `setVectorVisible` from recording — rejected
as it would silently produce broken scripts.

**Consequences:** Any future `VectorType` implementation added to `VectorTypes` must
implement `toScript()`. This is a soft contract enforced by the interface; omitting it
would cause a compilation error since `toScript()` has no default implementation.

---

*Last updated: Step 20 — v0.1 feature-complete*
*Backfill note: Entries D-001 through D-009 were reconstructed retrospectively.
D-010 onwards recorded in real time.*



