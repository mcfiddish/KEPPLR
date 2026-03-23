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
Synodic camera frame with the selected body as the "other body" — which was already
implemented. Maintaining a separate tracking concept would have duplicated behavior and
added dead state properties.

**Decision:** Tracking is removed as a distinct mode. The F key toggles the camera frame
between SYNODIC and INERTIAL. Stop Tracking has been removed from the View menu —
selecting Inertial in the Camera Frame submenu is the equivalent action. `trackedBodyId`,
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
Per-body priority rules — deferred as a future refinement (§16.5).

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
roll, setFov, setCameraPosition, setCameraOrientation, and setSynodicFrame.
All are non-blocking and use the existing TransitionController infrastructure
from step 18. Fine-grained mouse drag navigation remains in CameraInputHandler
and is intentionally unscriptable.

setSynodicFrame(focusNaifId, targetNaifId) changes only the camera frame — it
does not update focused, targeted, or selected body state in SimulationState.
(Note: `targetNaifId` is the method parameter name referring to the synodic
"other body" argument, not a reference to the targeted body interaction state.
The interactive `setCameraFrame(SYNODIC)` uses the selected body as the "other
body," not the targeted body.) setCameraFrame(SYNODIC) continues to derive
focus and "other body" from SimulationState for interactive use.

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
for setFov/setCameraPosition/setCameraOrientation). The coalesced result is flushed
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

## D-027: GLB shape model scale conventions
** Status:** Accepted
** Roadmap step:** 21

** Context:** GLB shape models are sourced from different pipelines with different authoring conventions. A single scale rule would either break body models or spacecraft models.
**Decision:** BodyBlock shape models are authored in kilometers — no scale transform is applied. SpacecraftBlock shape models are authored in meters — a uniform scale of 0.001 × SpacecraftBlock.scale() is applied at load time to convert to km. SpacecraftBlock.scale() defaults to 1.0 if not configured. These conventions are fixed; do not add runtime scale parameters or auto-detection logic.
**Consequences:** BodyNodeFactory applies no scale to body GLBs. The spacecraft loading path applies the scale to glbModelRoot at construction time and never updates it per-frame.

---

## D-028: Spacecraft GLB materials are used as-is
** Status:** Accepted
** Roadmap step:** 21

** Context:** Natural bodies go through KEPPLR's material pipeline (equirectangular texture mapping, textureAlignNode, center-longitude adjustment, PCK-driven orientation). Spacecraft models have their own PBR materials, colors, and textures embedded in the GLB, authored by the model creator.
** Decision:** Spacecraft GLBs use their embedded PBR materials without override. KEPPLR applies only the standard SamplerPreset.QUALITY_DEFAULT (Trilinear min, Bilinear mag, anisotropy 8) for texture quality. Spacecraft are lit by the scene sun DirectionalLight in the same way as natural bodies. KEPPLR's body material pipeline (equirectangular mapping, texture alignment, center-longitude) is never applied to spacecraft.
** Alternatives considered:** Applying KEPPLR's material pipeline to spacecraft — rejected because spacecraft models do not have equirectangular surface textures and their geometry is not a sphere.
** Consequences:** BodyNodeFactory (or equivalent) must not apply body material setup to spacecraft nodes. Any future spacecraft material override must be added explicitly and documented here.

---

## D-029: Spacecraft FK frame unified with PCK body-fixed frames throughout the rendering and camera stack
**Status:** Accepted
**Roadmap step:** 21 (post-completion refinement)

**Context:** After step 21, spacecraft have FK frames registered in `KEPPLREphemeris.stateTransformMap` (e.g. `NH_SPACECRAFT`). Two bugs resulted: (1) `BodySceneManager` never called `bsn.updateRotation()` for spacecraft — the GLB model never rotated. (2) `BodyFixedFrame` (camera co-rotation in BODY_FIXED mode) fell back to INERTIAL for spacecraft because `hasBodyFixedFrame()` only checked PCK body-fixed frames, not spacecraft FK frames.

**Decision:** `KEPPLREphemeris.hasBodyFixedFrame(EphemerisID)` and `getJ2000ToBodyFixed(EphemerisID)` now prefer `Spacecraft.frameID()` for bodies present in `spacecraftMap`, falling through to `spiceBundle.getBodyFixedFrame()` for natural bodies. Spacecraft FK frames are added to `stateTransformMap` at `KEPPLREphemeris` construction time alongside PCK frames, so the existing `stateTransformMap` lookup returns the correct transform for both. `BodySceneManager` now calls `bsn.updateRotation(rotation)` for spacecraft every frame alongside the position update.

**Alternatives considered:** A separate per-frame rotation code path for spacecraft — rejected because it would duplicate the logic already in the natural-body pass and add unnecessary branching.

**Consequences:** `hasBodyFixedFrame(-98)` now returns `true` for spacecraft with configured FK frames. `VectorTypesTest.returnsNullForNoOrientation` was updated to use NAIF -999 (present in no kernel and not configured as a spacecraft) as the no-frame test case, since NAIF -98 now legitimately has a frame.

---

## D-030: Spacecraft camera proximity scales with SpacecraftBlock.scale()
**Status:** Accepted
**Roadmap step:** 21 (post-completion refinement)

**Context:** `TransitionController.getBodyMinDist()` and `startGoToNow()` used `BODY_DEFAULT_RADIUS_KM = 1.0 km` as the fallback effective radius for any body with a null PCK shape. This gave spacecraft a minimum zoom distance of 1.1 km and a `goTo` arrival distance of ~6 km — far too large to see a meter-scale model.

**Decision:** For bodies with null shape, check for a `SpacecraftBlock`. If present, use `block.scale() × 0.001 km` as the effective radius (matching the GLB scale convention: meters × 0.001 = km). `BODY_DEFAULT_RADIUS_KM = 1.0` is retained as the fallback when no `SpacecraftBlock` exists, and as the radius used by `VectorRenderer` for arrow scaling on shape-less bodies. `CAMERA_ZOOM_FALLBACK_MIN_KM = 100.0` is reserved for the case where the body's `EphemerisID` cannot be resolved at all. The goTo formula — `endDist = effectiveRadius / tan(apparentRadiusDeg)` — and the `1.1× radius` minimum zoom rule both use this effective radius, producing visually consistent results for spacecraft at the same default `apparentRadiusDeg` (10°) used for natural bodies.

**Alternatives considered:** A separate `SpacecraftBlock.viewRadius` config field — rejected as redundant with the existing scale convention. Computing the GLB bounding box at load time — considered but rejected; the scale proxy is sufficient and avoids coupling the scene graph to the camera system.

**Consequences:** Minimum zoom and goTo arrival distance for spacecraft scale with `SpacecraftBlock.scale()`. A spacecraft with `scale = 1.0` has a minimum zoom of ~1.1 m and a goTo arrival of ~5.7 m from the model.

---

## D-031: Shape models hot-reloaded when a new configuration file is loaded
**Status:** Accepted
**Roadmap step:** 21 (post-completion refinement)

**Context:** `BodySceneManager` was constructed once in `simpleInitApp()`. Loading a new configuration via File → Load Configuration updated `KEPPLRConfiguration` but left the JME scene graph with geometry built from the old configuration. Shape models from the new config were never loaded.

**Decision:** `BodySceneManager.dispose()` detaches all managed body nodes from the frustum layer roots, calls `SaturnRingManager.detach()`, and clears internal maps. `KepplrApp.rebuildBodyScene()` (JME render thread) calls `dispose()`, re-registers the new `resourcesFolder()` as a `FileLocator`, and constructs a fresh `BodySceneManager`. `KepplrStatusWindow` calls a `configReloadCallback` (set by `KepplrApp` in `simpleInitApp()`) after a successful `KEPPLRConfiguration.reload()`. The callback enqueues `rebuildBodyScene()` via `KepplrApp.enqueue()`, ensuring the rebuild runs on the JME thread (CLAUDE.md Rule 4). `TrailManager` and `VectorManager` are not rebuilt — they hold no shape-model geometry and update naturally on the next frame.

**Alternatives considered:** Polling `KEPPLRConfiguration` for changes from `simpleUpdate()` — rejected as polling-based and unnecessarily coupling the render loop to config state. A `SimulationCommands.reloadConfig()` method — rejected because `BodySceneManager` is a render concern, not a simulation concern; the direct `enqueue()` callback is the established pattern already used for window resize.

**Consequences:** The `configReloadCallback` is a `Runnable` on `KepplrStatusWindow`, following the same pattern as `jmeResizeCallback`. Originally `rebuildBodyScene()` rebuilt only `BodySceneManager`; Step 22 expanded this to a full render manager restart — see D-032.

---

## D-032: Config reload is a full render manager restart
**Status:** Accepted
**Roadmap step:** 22

**Context:** D-031 introduced `rebuildBodyScene()` to rebuild `BodySceneManager` on config reload, but left `TrailManager`, `SunHaloRenderer`, `LabelManager`, `VectorManager`, and `StarFieldManager` alive with state from the old configuration. This was adequate when only shape models needed reloading, but Step 22 added `InstrumentFrustumManager` which reads IK kernels — also needing a full rebuild. The principle generalises: any render manager that reads from `KEPPLREphemeris` or `KEPPLRConfiguration` must be rebuilt when the configuration changes.

**Decision:** A config reload rebuilds all render managers. Managers with persistent scene-graph state (`TrailManager`, `SunHaloRenderer`, `LabelManager`) gained `dispose()` methods that remove their geometry from the scene graph and clear internal maps. `VectorManager` and `StarFieldManager` require no separate dispose step because they detach all geometry on every `simpleUpdate()` call; they are simply reconstructed with the same constructor arguments. `activeTrailIds` and `activeVectorDefs` in `KepplrApp` are cleared so the sync methods rebuild them from the current `SimulationState` on the next frame.

**Alternatives considered:** Selective rebuilds per manager type — rejected because the set of managers requiring rebuilds will grow and selective logic is fragile. Having each manager observe `KEPPLRConfiguration` reload events directly — rejected as coupling render concerns to the configuration lifecycle without a clear protocol.

**Consequences:** Any new render manager added in the future must either (a) have a `dispose()` method and be included in `rebuildBodyScene()`, or (b) be stateless per-frame (detach all geometry each update) and simply be reconstructed. This is the established pattern.

---

## D-033: CIRCLE/ELLIPSE FOV shapes approximated as n-gon polygons
**Status:** Accepted
**Roadmap step:** 22

**Context:** SPICE IK kernels define instrument FOVs as CIRCLE (1 bound vector), ELLIPSE (2 bound vectors), RECTANGLE (4), or POLYGON (3+). The frustum pyramid renderer allocates a `FloatBuffer` sized as `3×(2n−2)` vertices, which evaluates to 0 for n=1 and 6 for n=2 — both insufficient for the side-face loop that writes `n×9` floats. NH_REX, which has a 0.3° CIRCLE FOV, triggered a `BufferOverflowException` at render time.

**Decision:** `InstrumentFrustumManager.approximateBounds(FOVSpice)` converts CIRCLE and ELLIPSE FOVs to synthetic polygon bound lists before mesh allocation. CIRCLE: the single bound vector is rotated around the normalized boresight axis in `INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES = 32` equal steps using Rodrigues' rotation formula. ELLIPSE: 32 points are computed as `cos(t)·a + sin(t)·b` for the two semi-axis bound vectors. RECTANGLE and POLYGON pass through unchanged. The effective bounds are computed once at load time and stored in `FrustumEntry.effectiveBounds`; `updateMesh()` uses `effectiveBounds` rather than calling `fovSpice.getBounds()` each frame.

**Alternatives considered:** Skipping instruments with fewer than 3 bounds — rejected because a 0.3° circular FOV is meaningful and worth rendering. A separate rendering path for circular cones — rejected as additional complexity for no visual benefit at 32 sides. 16 sides — considered; 32 chosen as a better visual approximation at negligible extra cost.

**Consequences:** `INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES = 32` defined in `KepplrConstants`. All four SPICE FOV shape types are renderable. The degenerate fallback (boresight length < 1e-10) returns raw bounds and logs a warning; such instruments are skipped by the `getBounds().isEmpty()` check in `buildEntries()`.

---

## D-034: Manual sRGB gamma in EclipseLighting shader
**Status:** Accepted
**Roadmap step:** 23

**Context:** Body surface textures (equirectangular planet maps) are authored in sRGB, but JME's `setGammaCorrection(true)` only applies to its built-in Lighting/PBR pipeline. The custom `EclipseLighting` shader bypasses JME's lighting entirely — it computes N·L from a `SunPosition` uniform, not from scene lights. Enabling JME gamma correction would fix spacecraft PBR materials but leave the custom body shader unchanged, and could introduce inconsistencies between the two paths.

**Decision:** The `EclipseLighting` fragment shader performs manual `pow(c, 2.2)` on texture samples (sRGB→linear) and `pow(c, 1/2.2)` on output (linear→sRGB). The `BODY_AMBIENT_FACTOR` constant was lowered from 0.05 to 0.001 (linear) because the linearToSrgb output conversion lifts dark values — linear 0.001 ≈ sRGB 0.03, preserving the original perceptual night-side darkness. Flat `DiffuseColor` fallback (no texture) skips gamma conversion since engine-set colors are already linear.

**Alternatives considered:** `setGammaCorrection(true)` globally — rejected because it does not reach the custom shader and would require reworking the entire lighting pipeline. Tagging textures as sRGB and relying on hardware sRGB decode — considered but JME's GLSL 150 path does not reliably use `GL_SRGB` internal formats on all drivers.

**Consequences:** Body surface colors now match Cosmographia's muted tones. Any future custom shader that samples sRGB textures must include the same manual conversion. The Saturn ring shader (`SaturnRings.frag`) does not need it — its brightness/transparency textures are 1-D luminance profiles authored in linear space.

---

## D-035: Ring forward scatter is brighter than backscatter
**Status:** Accepted
**Roadmap step:** 23

**Context:** The original ring phase function (Step 16a) computed `halfPhase = 0.5 × (1 + dot(obsDir, sunDir))` and used it as brightness directly. This made backscatter (sun behind camera, `dot > 0`) bright and forward scatter (sun in front, `dot < 0`) dim — the opposite of physical reality. Saturn's rings are composed of ice particles that transmit and scatter light forward more strongly than they reflect it backward.

**Decision:** The scattering model now branches on which side of the ring plane the camera and Sun occupy. Same side (backscatter): brightness = `0.5 × (1 + cosPhase)`, bright at opposition and dimming toward 90° phase. Opposite sides (forward scatter): brightness = `unlitSideBrightness × (1 + strength × pow(cosForward, exponent))`, producing a concentrated brightness boost when looking through the rings toward the Sun. Three tunable constants control the lobe shape and unlit-side floor.

**Alternatives considered:** Single Henyey-Greenstein phase function — rejected as overly complex for the visual fidelity needed and harder to tune with named constants. Keeping the original polarity but boosting the dim side — rejected because the physical model is fundamentally inverted.

**Consequences:** `RING_FORWARD_SCATTER_STRENGTH`, `RING_FORWARD_SCATTER_EXPONENT`, and `RING_UNLIT_SIDE_BRIGHTNESS` defined in `KepplrConstants` and passed as shader uniforms. Shadow system unchanged — `shadowFactor` multiplies `ringLightFactor` after scattering is computed.

---

## D-036: Status window body readout shows NAIF ID and camera-to-body distance
**Status:** Accepted
**Roadmap step:** 27 (status window layout improvements)

**Context:** The body readout (Selected/Focused/Targeted) showed only the human-readable name (e.g., "Earth"). Users had no way to see the NAIF ID or how far the camera was from each body without mental calculation.

**Decision:** `SimulationStateFxBridge.formatBodyNameWithId()` formats bodies as "Name (ID)" (e.g., "Earth (399)"). `computeCameraToBodyDistanceKm()` computes the Euclidean distance from the camera heliocentric J2000 position to the body's heliocentric position via `KEPPLREphemeris.getHeliocentricPositionJ2000()`. `formatDistance()` auto-switches units: metres for < 1 km, km for < 0.01 AU, AU otherwise. Three distance thresholds defined in `KepplrConstants`: `KM_PER_AU`, `DISTANCE_DISPLAY_M_THRESHOLD_KM`, `DISTANCE_DISPLAY_AU_THRESHOLD_AU`. Distance properties are updated in both reactive listeners and `refreshAll()` polling.

**Alternatives considered:** Showing heliocentric distance instead of camera distance — rejected because camera distance is more actionable for navigation. Separate distance row per body — rejected in favour of inline distance (same row, right-aligned) to keep the readout compact.

**Consequences:** Three new `ReadOnlyStringProperty` fields on `SimulationStateFxBridge`. Body readout rows reordered to Focused → Targeted → Selected (focused is the camera anchor, most important).

---

## D-037: Status window layout: wider, always-on-top, section separators, live body filter
**Status:** Accepted
**Roadmap step:** 27 (status window layout improvements)

**Context:** The status window at 380px was too narrow for name + distance on one row. The body tree search field only resolved on Enter, requiring exact names. No visual separation existed between the body readout and status section. The transition progress bar was rarely noticed.

**Decision:** Window width increased to 440px. `stage.setAlwaysOnTop(true)` — user can minimise if needed. JavaFX `Separator` nodes inserted between body readout, status section, and body list. Transition progress bar removed. Body tree search field replaced with live filtering: on each keystroke, a filtered copy of the master tree is built, keeping items whose display name or NAIF ID contains the filter text (case-insensitive). Parent groups are included (expanded) if any child matches, or if the group name itself matches. Enter still resolves exact NAIF IDs via `BodyLookupService.resolve()`. The Clear button on the Selected row was removed; only Focus and Target buttons remain.

**Alternatives considered:** CSS stylesheet instead of inline styles — deferred to a future polish pass. Collapsible status section — rejected as over-engineering for the current use case.

**Consequences:** `masterRoot` field added to `KepplrStatusWindow` to preserve the unfiltered tree. `buildFilteredRoot()` and `matchesFilter()` helper methods added. `ProgressBar` import removed. Section header renamed from "Bodies" to "Select Body" for clarity.

---

## D-038: Overlays menu bidirectionally synced to SimulationState visibility properties
**Status:** Accepted
**Roadmap step:** 27

**Context:** The "Current Focus" submenu in the Overlays menu maintained its own checked state. When a user toggled a trail or axes via the body tree context menu (or a script), the overlays menu checkmarks became stale. The two menus were visually inconsistent.

**Decision:** Each `CheckMenuItem` in the "Current Focus" submenu is bound to the corresponding `SimulationState` visibility property (`trailVisibleProperty`, `vectorVisibleProperty`) for the currently focused body. `ChangeListener` instances are added when focus is set and removed (unbound) when focus changes. Initial state is synced immediately on bind. A `Runnable[] unbindPrev` array-of-one pattern stores the cleanup action.

**Alternatives considered:** Rebuilding the menu items on each show event — rejected because it would lose the menu's position/state mid-interaction. Polling via AnimationTimer — rejected as wasteful when property listeners are available.

**Consequences:** Any source of visibility changes (context menu, scripts, keyboard shortcuts) is automatically reflected in the overlays menu. No additional wiring needed when new visibility sources are added.

---

## D-039: Body tree context menu uses CheckMenuItem with dynamic state
**Status:** Accepted
**Roadmap step:** 27

**Context:** The context menu initially used "Show/Hide" text toggling (e.g., "Show Trail" / "Hide Trail"). The status window menus used `CheckBox` inside `CustomMenuItem`. This was visually inconsistent.

**Decision:** All toggle items across the application use `CheckMenuItem`. The context menu's `populateBodyTreeContextMenu()` reads current visibility state from `SimulationState` at show time and sets `CheckMenuItem.setSelected()` accordingly. The overlays menu, instruments menu, and File > Record Session also use `CheckMenuItem`. The `menuCheckBox` helper and `CheckBox` import were removed.

**Alternatives considered:** Using `CheckBox` + `CustomMenuItem` everywhere (allows tooltips) — rejected because `CheckMenuItem` is the standard JavaFX pattern and tooltips on toggle items are low-value.

**Consequences:** Consistent checkmark-style toggles everywhere. `CustomMenuItem` is still used for non-toggle items that need tooltips (e.g., `tipItem` helper for action items, radio buttons for camera frame).

---

## D-040: Body-fixed axes scale to origin body radius, not focused body radius
**Status:** Accepted
**Roadmap step:** 27

**Context:** All vector overlays scaled their arrow length relative to the focused body's mean radius. When viewing a spacecraft's body-fixed axes while focused on a planet, the axes were enormous (planet-radius scale). Conversely, if the spacecraft was focused but had no PCK shape data, axes used the 1.0 km fallback — ignoring the GLB bounding radius.

**Decision:** Added `VectorType.usesOriginBodyRadius()` default method (returns `false`). `BodyAxisVectorType` overrides it to return `true`. `VectorRenderer.update()` now accepts an `IntToDoubleFunction sceneRadiusLookup` parameter (supplied as `bodySceneManager::getEffectiveBodyRadiusKm`). When `usesOriginBodyRadius()` is true, the arrow length is computed from the origin body's effective rendered radius (scene-derived, including GLB bounding radius and spacecraft `scale()` factor), with ephemeris shape fallback, then `BODY_DEFAULT_RADIUS_KM` fallback. Other vector types (velocity, towardBody) continue to use the focused body's radius.

**Alternatives considered:** Adding a per-VectorDefinition radius override — rejected as over-engineering; the VectorType already knows whether it's body-intrinsic. Querying ephemeris only — rejected because spacecraft have no PCK shape entry; the scene-derived radius from `BodySceneManager` is the only source that accounts for GLB bounding radius and configured scale.

**Consequences:** `VectorManager.update()` and `VectorRenderer.update()` signatures gain an `IntToDoubleFunction` parameter. `KepplrApp` passes `bodySceneManager::getEffectiveBodyRadiusKm`. Six new tests cover `usesOriginBodyRadius()` for all built-in types plus a custom default.

---

## D-041: Trail period for comparable-mass binaries uses relative orbit
**Status:** Accepted
**Roadmap step:** 24

**Context:** `TrailSampler.computeTrailDurationSec` computed the orbital period of a satellite by feeding its state relative to the system **barycenter** and the system GM into `oscltx`. For the two-body period formula T = 2π√(a³/GM), `a` must be the semi-major axis of the **relative** orbit (body-to-body separation), not the body-to-barycenter distance. For most planet–satellite pairs (mass ratio ≫ 1) the barycenter is inside the primary and the error is negligible (~2% for Earth–Moon). For Pluto–Charon (mass ratio ~8.5:1) the barycentric semi-major axis is ~2,035 km vs. the relative semi-major axis of ~19,571 km, producing a period 30× too short (~5 hours instead of 6.387 days). The trail showed only ~12° of arc — effectively invisible.

**Decision:** Compute the period from the state of the satellite relative to the **primary body** (NAIF code `barycenterId × 100 + 99`) rather than relative to the barycenter. The system GM is still correct for the relative orbit: T = 2π√(a_rel³ / G(M₁+M₂)) is exact. For Pluto (999), where the primary code equals the satellite code itself, the companion body (`barycenterId × 100 + 1`, i.e. Charon 901) is used instead. Falls back to the barycenter if neither primary nor companion can be resolved.

**Future work:** The NAIF naming convention (primary = x99, satellites = x01–x98, barycenter = x) does not hold for asteroids with satellites. A `primary` parameter in `KEPPLRConfiguration.bodyBlock()` will be needed to support those systems. See roadmap future items.

**Alternatives considered:** Correcting the barycentric period with an effective GM = G·M₂³/(M₁+M₂)² — rejected because it requires individual body masses that may not be in the kernel pool. Using the barycentric state unchanged — rejected because the 30× error for Pluto makes the trail invisible.

**Consequences:** `TrailSampler.computeTrailDurationSec` now resolves the primary body for all satellites. Existing behaviour for standard satellites is unchanged (relative orbit ≈ barycentric orbit when mass ratio is extreme). Pluto's trail correctly shows the full ~6.387-day orbit around the Pluto Barycenter.

---

## D-042: Barycenter label and trail filtering
**Status:** Accepted
**Roadmap step:** 24

**Context:** The Overlays → Labels and Overlays → Trajectories global toggles iterated over all known bodies. Barycenters (NAIF codes 1–9) received labels and trails, cluttering the view with redundant overlays that overlap the planet they represent. However, the Pluto Barycenter (9) is a meaningful distinct object because Pluto (999) visibly orbits it.

**Decision:** When enabling labels or trails via the global toggle, skip NAIF codes 1–8 (planet system barycenters). Pluto Barycenter (9) is exempted from the skip. When disabling, turn all off including barycenters. Per-body toggles in context menus are unaffected.

**Alternatives considered:** Filtering all barycenters including Pluto — rejected because the Pluto–Charon barycenter is visually distinct from either body. No filtering — rejected because barycenter labels overlap planet labels at all zoom levels.

**Consequences:** `KepplrStatusWindow.applyLabelVisibility` and the Trajectories toggle handler both skip codes 1–8. Pluto Barycenter labels and trails appear when toggled on.

---

## D-043: Screenshot capture runs post-render, not pre-simpleUpdate
**Status:** Accepted
**Roadmap step:** 25

**Context:** The initial implementation used `KepplrApp.enqueue()` to schedule framebuffer capture. In JME's `Application.update()` loop, enqueued tasks run at the start of the frame — before `simpleUpdate()` and before the render pass. This meant the capture read the framebuffer from the *previous* frame's render. During animation sequences, `setET()` advanced the simulation clock and `applyFocusTracking()` moved the camera to follow the focus body, but neither had executed yet when the capture ran. Every captured frame showed the camera at its initial position rather than tracking the focus body.

**Decision:** `KepplrApp` overrides `update()` instead of using `enqueue()`. A `volatile PendingCapture` record (outputPath + CountDownLatch) is set by the screenshot callback from the capture/script thread. `update()` calls `super.update()` first (which runs enqueued tasks → `simpleUpdate()` → render pass), then checks for and processes any pending capture. The framebuffer now contains the fully rendered scene at the current ET with focus-body tracking, body-fixed/synodic camera frame rotation, and all render managers updated.

**Alternatives considered:** Double-enqueue (one fence frame, then one capture frame) — rejected as fragile and adding unnecessary latency. `SceneProcessor.postFrame()` — considered but the `update()` override is simpler and requires no additional JME interface implementation.

**Consequences:** Each `saveScreenshot()` call blocks until the JME thread completes one full update cycle (simpleUpdate + render + capture). The volatile field ensures visibility across threads. The capture thread and JME thread are serialised per frame via the CountDownLatch.

---

## D-044: Log window with ANSI color rendering
**Status:** Accepted
**Roadmap step:** 25

**Context:** Log output was only visible on the console. Users running KEPPLR from a launcher or IDE often could not see log messages. A JavaFX log window was needed, but the `%highlight` pattern in log4j2 produces ANSI escape sequences (e.g., `\033[32m` for green) that would appear as garbage in a plain `TextArea`.

**Decision:** `LogAppender` (package-private, `kepplr.ui`) is a custom `AbstractAppender` registered programmatically on all loggers via the `LoggerContext` configuration, following the same pattern as `Log4j2Configurator.addFile()`. It uses a `PatternLayout` with the pattern from `KEPPLRConfiguration.getInstance().logFormat()` and `disableAnsi=false` to force ANSI output. Log lines are buffered in a `ConcurrentLinkedQueue`. `LogWindow` (package-private, `kepplr.ui`) displays log output in a `TextFlow` inside a `ScrollPane` on a dark background (`#1e1e1e`). An ANSI parser (`Pattern`-based) converts SGR codes to styled `Text` nodes with appropriate foreground colors. The queue is drained on each FX frame pulse from the existing `AnimationTimer` in `KepplrStatusWindow`, avoiding `Platform.runLater()` per CLAUDE.md Rule 2. A "Save Log..." button writes ANSI-stripped plain text via `FileChooser`. Accessible from `File → Show Log`.

**Alternatives considered:** `TextArea` with no color — rejected because the user specifically wanted ANSI colors for level distinction. RichTextFX library — rejected to avoid an external dependency. `Platform.runLater()` per log event — rejected per CLAUDE.md Rule 2.

**Consequences:** Max 50,000 `Text` nodes retained; oldest are trimmed. The `LogAppender` reads `KEPPLRConfiguration.getInstance().logFormat()` at install time (point-of-use, Rule 3). The `logFormat()` method is on the user-owned `KEPPLRConfiguration` class.

---

## D-045: Frame-relative camera position and orientation commands
**Status:** Accepted
**Roadmap step:** 28

**Context:** `setCameraPosition` and `setCameraOrientation` interpreted their vector arguments in J2000 regardless of the active camera frame. A user who set up a synodic frame and then called `setCameraOrientation(1,0,0, 0,0,1, 5)` expected to look along the synodic +X axis (toward the target body), but instead got J2000 +X (vernal equinox).

**Decision:** `TransitionController.frameToJ2000(VectorIJK)` transforms vectors from the active camera frame to J2000 before applying them. For SYNODIC, the three `SynodicFrame.Basis` vectors are the columns of the synodic-to-J2000 rotation matrix: `new RotationMatrixIJK(basis.xAxis(), basis.yAxis(), basis.zAxis())` then `mxv`. For BODY_FIXED, the J2000-to-body-fixed rotation is transposed via `rot.mtxv()`. INERTIAL is the identity (no transform). Both `handleCameraPositionRequest` and `handleCameraOrientationRequest` pass their end vectors through `frameToJ2000`.

**Alternatives considered:** Leaving commands always in J2000 and requiring scripts to do the math — rejected because the whole point of camera frames is to simplify spatial reasoning.

**Consequences:** Scripts can now reason about camera positioning in the natural frame of the scene. The transform uses the frame/focus/target state at the moment the request is processed (first JME frame), not when it was issued.

---

## D-046: Body visibility toggle with barycenters hidden by default
**Status:** Accepted
**Roadmap step:** 28

**Context:** Barycenters (NAIF IDs 0–9) are rendered as point sprites that often overlap with or obscure the planet they represent (e.g., Pluto barycenter vs. Pluto). The user wanted a way to show/hide individual bodies, with barycenters hidden by default.

**Decision:** `SimulationState.bodyVisibleProperty(int)` returns a `ReadOnlyBooleanProperty` backed by a `ConcurrentHashMap<Integer, SimpleBooleanProperty>` in `DefaultSimulationState`, defaulting to `true`. An instance initializer pre-populates NAIF IDs 0–9 with `false`. `BodySceneManager.update()` checks the flag before pass 1 for natural bodies and before the spacecraft loop; hidden bodies are skipped and any stale scene node is detached. `SimulationCommands.setBodyVisible(int, boolean)` is the command; `KepplrScript` exposes both int and String overloads. The body tree context menu in `KepplrStatusWindow` includes a "Visible" `CheckMenuItem`.

**Alternatives considered:** Filtering barycenters unconditionally in the render loop — rejected because the user sometimes wants to see them. Using a configuration file list — rejected because this is a runtime toggle, not a persistent setting.

**Consequences:** Barycenters are hidden on startup but can be toggled from the context menu or via `kepplr.setBodyVisible(9, true)`. The visibility map is per-session (not persisted across restarts).

---

*Last updated: Step 28 (complete)*
*Backfill note: Entries D-001 through D-009 were reconstructed retrospectively.
D-010 onwards recorded in real time.*



