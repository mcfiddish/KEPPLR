================
Groovy Scripting
================

KEPPLR can be automated using `Groovy <https://groovy-lang.org/>`__ scripts.  Scripts have access to a
``kepplr`` object (an instance of
`KepplrScript <file:_static/javadoc/kepplr/scripting/KepplrScript.html>`__) that provides methods for
controlling the simulation, camera, and overlays.

Run a script using ``File > Run Script`` from the GUI, or enter commands directly in the Script Console
at the bottom of the control window.  See :doc:`gui` for details.

For ready-to-use examples, see :doc:`scripting_examples`.


Basic Concepts
--------------

**Blocking vs. non-blocking methods.**
Most methods that move the camera (``goTo``, ``pointAt``, ``orbit``, ``setCameraPosition``, etc.) start
an animated transition and return immediately.  Use ``waitTransition()`` to pause the script until the
transition completes.  Methods that change state instantly (``selectBody``, ``targetBody``,
``centerBody``, ``setPaused``, ``setTimeRate``) take effect immediately.

**Identifying bodies.**
Methods that take a body accept either a NAIF ID (integer) or a body name (string).  For example,
``kepplr.centerBody(399)`` and ``kepplr.centerBody("Earth")`` are equivalent.

**Coordinate frames.**
Camera position and orientation methods (``setCameraPosition``, ``setCameraOrientation``) interpret their
arguments in the active camera frame (INERTIAL, BODY_FIXED, or SYNODIC).  Set the frame with
``setCameraFrame()`` before positioning the camera.

**Units.**
Distances are in kilometers.  Angles are in degrees.  Times are in seconds (simulation or wall-clock,
depending on the method).


API Reference
-------------

The following sections list the methods available on the ``kepplr`` object.  All methods that accept a
body name also have an overload that accepts a NAIF ID (integer).


Body Selection
++++++++++++++

.. list-table::
   :widths: 38 32 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``selectBody(int/String body)``
     - Set the selected body.  Does not move the camera.
     - ``kepplr.selectBody("Earth")``
   * - ``targetBody(int/String body)``
     - Set the targeted body.  Also selects it.  Does not move the camera.
     - ``kepplr.targetBody("Mars")``
   * - ``centerBody(int/String body)``
     - Set the centered body.  Also targets and selects it.  Does not move the camera.
     - ``kepplr.centerBody("Jupiter")``


Time Control
++++++++++++

.. list-table::
   :widths: 38 32 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``setPaused(boolean paused)``
     - Pause or resume the simulation clock.
     - ``kepplr.setPaused(true)``
   * - ``setTimeRate(double rate)``
     - Set the time rate (simulated seconds per wall-clock second).
     - ``kepplr.setTimeRate(3600)``
   * - ``setET(double et)``
     - Set the simulation time as TDB seconds past J2000.
     - ``kepplr.setET(490078800.0)``
   * - ``setUTC(String utcString)``
     - Set the simulation time from a UTC string (e.g., ``"2015 Jul 14 08:00:00"``).
     - ``kepplr.setUTC("2015 Jul 14 07:59:00")``


Camera Motion
+++++++++++++

These methods start animated transitions.  Use ``waitTransition()`` to block until the transition completes.

.. list-table::
   :widths: 42 28 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``goTo(int/String body, double apparentRadiusDeg, double durationSec)``
     - Move the camera so that the body fills the given apparent radius in degrees.  Also sets
       the selected, focused, and targeted bodies to the approached body, and prepends a default
       ``pointAt`` slew.
     - ``kepplr.goTo("Earth", 15.0, 5.0); kepplr.waitTransition()``
   * - ``pointAt(int/String body, double durationSec)``
     - Slew the camera to point at the given body.  Also sets the selected and targeted bodies
       to that body.
     - ``kepplr.pointAt("Moon", 3.0); kepplr.waitTransition()``
   * - ``zoom(double factor, double durationSec)``
     - Zoom by the given factor (>1 zooms in, <1 zooms out).
     - ``kepplr.zoom(2.0, 2.0); kepplr.waitTransition()``
   * - ``setFov(double degrees, double durationSec)``
     - Animate the field of view to the given value in degrees.
     - ``kepplr.setFov(30.0, 3.0); kepplr.waitTransition()``
   * - ``orbit(double rightDeg, double upDeg, double durationSec)``
     - Orbit the camera around the focused body by the given angles.
     - ``kepplr.orbit(45.0, 0.0, 4.0); kepplr.waitTransition()``
   * - ``tilt(double degrees, double durationSec)``
     - Tilt the camera up (positive) or down (negative).
     - ``kepplr.tilt(20.0, 2.0); kepplr.waitTransition()``
   * - ``yaw(double degrees, double durationSec)``
     - Yaw the camera left (positive) or right (negative).
     - ``kepplr.yaw(-10.0, 5.0); kepplr.waitTransition()``
   * - ``roll(double degrees, double durationSec)``
     - Roll the camera.
     - ``kepplr.roll(90.0, 3.0); kepplr.waitTransition()``
   * - ``truck(double km, double durationSec)``
     - Move the camera left/right by the given distance over the given duration.
     - ``kepplr.truck(500.0, 2.0); kepplr.waitTransition()``
   * - ``truck(double km)``
     - Move the camera left/right using the default cinematic duration.
     - ``kepplr.truck(500.0); kepplr.waitTransition()``
   * - ``crane(double km, double durationSec)``
     - Move the camera up/down by the given distance over the given duration.
     - ``kepplr.crane(200.0, 2.0); kepplr.waitTransition()``
   * - ``crane(double km)``
     - Move the camera up/down using the default cinematic duration.
     - ``kepplr.crane(200.0); kepplr.waitTransition()``
   * - ``dolly(double km, double durationSec)``
     - Move the camera forward/backward by the given distance over the given duration.
     - ``kepplr.dolly(-1000.0, 2.0); kepplr.waitTransition()``
   * - ``dolly(double km)``
     - Move the camera forward/backward using the default cinematic duration.
     - ``kepplr.dolly(-1000.0); kepplr.waitTransition()``
   * - ``setCameraPosition(double x, double y, double z, double durationSec)``
     - Move the camera to the given position in the active frame, relative to the focused body.
     - ``kepplr.setCameraPosition(0, 0, 50000.0, 5.0); kepplr.waitTransition()``
   * - ``setCameraPosition(double x, double y, double z, int/String origin, double durationSec)``
     - Move the camera to the given position relative to a body other than the focused body.
     - ``kepplr.setCameraPosition(-0.050, 0.030, 0, -98, 5); kepplr.waitTransition()``
   * - ``setCameraOrientation(double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSec)``
     - Set the camera look and up directions in the active frame.
     - ``kepplr.setCameraOrientation(1,0,0, 0,0,1, 3.0); kepplr.waitTransition()``
   * - ``cancelTransition()``
     - Cancel any in-progress camera transition.
     - ``kepplr.cancelTransition()``


Camera Frame
++++++++++++

.. list-table::
   :widths: 38 32 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``setCameraFrame(CameraFrame frame)``
     - Set the camera frame.  Use ``CameraFrame.INERTIAL``, ``CameraFrame.BODY_FIXED``, or
       ``CameraFrame.SYNODIC``.
     - ``kepplr.setCameraFrame(CameraFrame.BODY_FIXED)``
   * - ``setSynodicFrame(int/String focusBody, int/String targetBody)``
     - Set up a synodic frame between two bodies and switch to it.
     - ``kepplr.setSynodicFrame(-98, 999)``


Overlays and Visibility
+++++++++++++++++++++++

.. list-table::
   :widths: 40 30 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``setLabelVisible(int/String body, boolean visible)``
     - Show or hide the label for a body.
     - ``kepplr.setLabelVisible("Pluto", true)``
   * - ``setAllLabelsVisible(boolean visible)``
     - Show or hide all labels.
     - ``kepplr.setAllLabelsVisible(true)``
   * - ``setTrailVisible(int/String body, boolean visible)``
     - Show or hide the orbit trail for a body.
     - ``kepplr.setTrailVisible(399, true)``
   * - ``setAllTrailsVisible(boolean visible)``
     - Show or hide all orbit trails.
     - ``kepplr.setAllTrailsVisible(false)``
   * - ``setTrailDuration(int/String body, double seconds)``
     - Set the duration of a body's orbit trail in seconds.
     - ``kepplr.setTrailDuration("Earth", 31557600.0)``
   * - ``setTrailReferenceBody(int/String body, int/String referenceBody)``
     - Set the reference body for a trail and its velocity arrow.  The trail shows the body's
       position relative to ``referenceBody``.  Pass ``-1`` (integer form) to restore the
       default heuristic: natural satellites use their system barycenter; planets, barycenters,
       and spacecraft have no reference body and are drawn in inertial J2000.  The velocity
       arrow always points in the same direction as the trail.
     - ``kepplr.setTrailReferenceBody("Europa", "Io")``
   * - ``setVectorVisible(int/String body, VectorType type, boolean visible)``
     - Show or hide a vector overlay.  Vector types include ``VectorTypes.velocity()``,
       ``VectorTypes.towardBody(int naifId)``, ``VectorTypes.bodyAxisX()``, etc.
     - ``kepplr.setVectorVisible(399, VectorTypes.velocity(), true)``
   * - ``setBodyFixedAxesVisible(int/String body, boolean visible)``
     - Show or hide all three body-fixed axes (X, Y, Z) at once.
     - ``kepplr.setBodyFixedAxesVisible("Mars", true)``
   * - ``setFrustumVisible(int naifCode, boolean visible)``
     - Show or hide an instrument frustum by NAIF code.
     - ``kepplr.setFrustumVisible(-98300, true)``
   * - ``setFrustumVisible(String instrument, boolean visible)``
     - Show or hide an instrument frustum by name.
     - ``kepplr.setFrustumVisible("NH_LORRI", true)``
   * - ``setBodyVisible(int/String body, boolean visible)``
     - Show or hide a body entirely.
     - ``kepplr.setBodyVisible(9, false)``
   * - ``setHudTimeVisible(boolean visible)``
     - Show or hide the UTC time display.
     - ``kepplr.setHudTimeVisible(false)``
   * - ``setHudInfoVisible(boolean visible)``
     - Show or hide the selected-body info display.
     - ``kepplr.setHudInfoVisible(true)``
   * - ``setRenderQuality(RenderQuality quality)``
     - Set the render quality.  Use ``RenderQuality.LOW``, ``RenderQuality.MEDIUM``, or
       ``RenderQuality.HIGH``.
     - ``kepplr.setRenderQuality(RenderQuality.HIGH)``


.. note::

   **Trail coordinate frame and reference body.**

   Trails are only drawn in a rotating frame (SYNODIC or BODY_FIXED) when the body has
   a **reference body** — either the default heuristic or one set explicitly with
   ``setTrailReferenceBody``.

   *Default reference bodies:*

   * **Natural satellites** (NAIF 100–999, not ending in 99, plus Pluto) — their system
     barycenter is used automatically (e.g. Europa → Jupiter Barycenter).
   * **Major planets** (e.g. Earth = 399, Mars = 499), **barycenters**, and **spacecraft** —
     no reference body by default.  These trails are always drawn in inertial J2000
     **regardless of the active camera frame**, even when SYNODIC or BODY_FIXED is active.

   *Effect of the camera frame:*

   * ``INERTIAL`` — trail is drawn relative to the reference body (if any) in J2000.  Bodies
     without a reference body are drawn in absolute heliocentric J2000.
   * ``SYNODIC`` — for bodies *with* a reference body, sample positions are projected into the
     co-rotating frame at each sample epoch and re-expressed at render time, so the trail
     appears frozen in the rotating frame.  Bodies *without* a reference body are unaffected
     and still render in inertial J2000.
   * ``BODY_FIXED`` — same condition as SYNODIC; additionally, the reference body is forced
     to the focus body, overriding any ``setTrailReferenceBody`` setting.

   *Using* ``setTrailReferenceBody`` *with planets or spacecraft:*
   Call ``setTrailReferenceBody`` to assign a reference body to any body that lacks one.
   Once set, the rotating-frame paths activate for that body just as they do for satellites.
   For example, ``kepplr.setTrailReferenceBody("Europa", "Io")`` overrides Europa's default
   (Jupiter Barycenter) and draws its trail relative to Io; switching to SYNODIC then freezes
   that relative trajectory in the co-rotating frame, revealing the Laplace resonance loops.


Output
++++++

.. list-table::
   :widths: 40 30 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``saveScreenshot(String path)``
     - Save the current display as a PNG file.
     - ``kepplr.saveScreenshot("/tmp/frame.png")``
   * - ``captureSequence(String outputDir, double startET, int frameCount, double etStep)``
     - Capture a sequence of frames as PNG images.
     - ``kepplr.captureSequence("/tmp/out/", 490078800.0, 120, 6.0)``
   * - ``captureSequence(String outputDir, String startUTC, int frameCount, double etStep)``
     - Capture a sequence of frames, specifying the start time as a UTC string.
     - ``kepplr.captureSequence("/tmp/out/", "2015 Jul 14 07:00:00", 4800, 6)``
   * - ``setWindowSize(int width, int height)``
     - Resize the display window.
     - ``kepplr.setWindowSize(1920, 1080)``
   * - ``displayMessage(String text)``
     - Display a message on the HUD for the default duration.
     - ``kepplr.displayMessage("Approaching Pluto")``
   * - ``displayMessage(String text, double durationSec)``
     - Display a message on the HUD for the given number of wall-clock seconds.
     - ``kepplr.displayMessage("Go to New Horizons", 5.0)``


State
+++++

.. list-table::
   :widths: 38 32 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``getState()``
     - Return the live `SimulationState <file:_static/javadoc/kepplr/state/SimulationState.html>`__ instance.  Scripts
       can read any observable property: current ET, time rate, paused status, focused body ID, camera position, etc.
     - ``et = kepplr.getState().currentEtProperty().get()``
   * - ``getConfiguration()``
     - Return the current `KEPPLRConfiguration <file:_static/javadoc/kepplr/config/KEPPLRConfiguration.html>`__ instance,
       giving access to the ephemeris, time conversion utilities, and loaded kernel data.
     - ``eph = kepplr.getConfiguration().getEphemeris()``
   * - ``getStateString()``
     - Return a string encoding the full simulation state (camera, time, focused body, etc.).
     - ``def s = kepplr.getStateString()``
   * - ``setStateString(String stateString)``
     - Restore simulation state from a previously saved state string.
     - ``kepplr.setStateString(s)``
   * - ``loadConfiguration(String path)``
     - Load a new configuration file, replacing the current bodies and kernels.
     - ``kepplr.loadConfiguration("/path/to/nh.config")``


Waiting
+++++++

.. list-table::
   :widths: 38 32 30
   :header-rows: 1

   * - Method
     - Description
     - Example
   * - ``waitTransition()``
     - Block until any in-progress camera transition completes.
     - ``kepplr.goTo("Saturn", 10, 5); kepplr.waitTransition()``
   * - ``waitWall(double seconds)``
     - Pause the script for the given number of wall-clock seconds.
     - ``kepplr.waitWall(2.0)``
   * - ``waitSim(double seconds)``
     - Pause the script until the given number of simulated seconds have elapsed.
     - ``kepplr.setTimeRate(3600); kepplr.waitSim(86400)``
   * - ``waitUntilSim(double targetET)``
     - Pause the script until the simulation reaches the given ET.
     - ``kepplr.waitUntilSim(490082400.0)``
   * - ``waitUntilSim(String utcString)``
     - Pause the script until the simulation reaches the given UTC time.
     - ``kepplr.waitUntilSim("2015 Jul 14 08:00:00")``
