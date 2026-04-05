==================
Scripting Examples
==================

This page provides ready-to-use Groovy scripts for common KEPPLR tasks.  Run any example with
``File > Run Script`` or paste it into the Script Console.  For the full API reference, see
:doc:`scripting`.


Tour of the Inner Solar System
------------------------------

Fly the camera to each of the inner planets in sequence.

.. code-block:: groovy

   kepplr.setUTC("2024 Jan 01 00:00:00")
   kepplr.setTimeRate(1.0)

   ["Mercury", "Venus", "Earth", "Mars"].each { body ->
       kepplr.goTo(body, 15.0, 4.0)
       kepplr.waitTransition()
       kepplr.displayMessage(body, 3.0)
       kepplr.waitWall(3.0)
   }


Watch a Full Orbit
------------------

Focus on a body, enable its trail, and fast-forward through one orbital period.

.. code-block:: groovy

    kepplr.centerBody("SUN")
    kepplr.setCameraPosition(0,5e8,0,5)
    kepplr.waitTransition()
    kepplr.setCameraOrientation(0,-1,0,0,1,0,5)
    kepplr.waitTransition()
    kepplr.selectBody("Mars")

    def marsYear = 687*86400
    kepplr.setTrailVisible("Mars", true)
    kepplr.setTrailDuration("Mars", marsYear)

    kepplr.setTimeRate(500000.0)
    kepplr.waitSim(marsYear)
    kepplr.setPaused(true)


Earth-Moon System Close-Up
--------------------------

Zoom in on the Earth and watch the Moon orbit.  Show the Moon's velocity vector.

.. code-block:: groovy

    kepplr.setUTC("2024 Mar 25 00:00:00")

    kepplr.centerBody("Earth")
    kepplr.setCameraPosition(0,1e6,0,5)
    kepplr.waitTransition()
    kepplr.setCameraOrientation(0,-1,0,0,1,0,5)
    kepplr.waitTransition()
    kepplr.selectBody("Moon")
    kepplr.setFov(40, 5)

    kepplr.setVectorVisible("Moon", VectorTypes.velocity(), true)
    kepplr.setLabelVisible("Moon", true)

    def month = 27.3 * 86400
    kepplr.setTimeRate(50000.0)
    kepplr.waitSim(month)
    kepplr.setPaused(true)



Capture a Screenshot Sequence
-----------------------------

Generate a series of frames suitable for encoding into a video.  Each frame advances the simulation
by a fixed time step.

.. code-block:: groovy

   kepplr.setWindowSize(1920, 1080)

   kepplr.goTo("Jupiter", 12.0, 3.0)
   kepplr.waitTransition()

   // 600 frames, 10 minutes per frame = 10 Jupiter rotation periods
   kepplr.captureSequence("/tmp/jupiter_frames/", "2024 Jun 01 00:00:00", 600, 600.0)

Create an animation with :doc:`tools/PngToMovie` (assumes you have `ffmpeg` in your $PATH):

::

    PngToMovie -fps 30 -out jupiter.webm -seq /tmp/jupiter_frames

You can view the `.webm` file in a browser.

Orbit Around a Body
--------------------

Orbit the camera 360 degrees around Saturn for a cinematic sweep.

.. code-block:: groovy

   kepplr.goTo("Saturn", 10.0, 5.0)
   kepplr.waitTransition()
   kepplr.setPaused(true)

   // Full orbit in 36 steps of 10 degrees
   36.times {
       kepplr.orbit(10.0, 0.0, 0.5)
       kepplr.waitTransition()
   }


Save and Restore State
----------------------

Capture the current view as a compact string, then restore it later.

.. code-block:: groovy

   // Save the current state
   def saved = kepplr.getStateString()
   kepplr.displayMessage("State saved")

   // ... do other things, move the camera, change time ...

   // Restore
   kepplr.setStateString(saved)
   kepplr.displayMessage("State restored")


Body-Fixed Camera Frame
-----------------------

Switch to the body-fixed frame to watch a planet rotate beneath the camera.

.. code-block:: groovy

   kepplr.goTo("Earth", 20.0, 4.0)
   kepplr.waitTransition()

   kepplr.setCameraFrame(CameraFrame.BODY_FIXED)
   kepplr.setTimeRate(3600.0)
   kepplr.displayMessage("Body-fixed frame: Earth rotates beneath the camera")


Synodic Frame
-------------

View the Earth-Moon system from a synodic (co-rotating) frame where both bodies appear stationary.

.. code-block:: groovy

   kepplr.centerBody("Earth")
   kepplr.selectBody("Moon")
   kepplr.setCameraFrame(CameraFrame.SYNODIC)
   kepplr.setCameraPosition(-1e6,5e4,0,5)
   kepplr.waitTransition()
   kepplr.setCameraOrientation(1,-0.05,0,0,0,1,5)
   kepplr.waitTransition()
   kepplr.setFov(2,5)

   kepplr.setVectorVisible("Moon", VectorTypes.towardBody(10), true)
   kepplr.setVectorVisible("Moon", VectorTypes.velocity(), true)

   kepplr.setLabelVisible("Moon", true)
   kepplr.setTimeRate(100000.0)
   kepplr.displayMessage("Synodic frame: Earth-Moon system co-rotating")



Laplace Resonance of the Galilean Moons
----------------------------------------

Visualise the 1:2:4 orbital resonance of Io, Europa, and Ganymede.

By drawing Europa's and Ganymede's trails **relative to Io** while the camera
rotates with Io's orbit (synodic frame), the resonance geometry becomes visible
as closed curves.  Over exactly four Io orbital periods Europa traces a
kidney-bean shape and Ganymede traces a three-petal rose — a direct visual
fingerprint of the Laplace resonance that prevents these three moons from ever
aligning on the same side of Jupiter.

.. code-block:: groovy

    // NAIF IDs for the inner Galilean moons
    def io                = 501
    def europa            = 502
    def ganymede          = 503

    kepplr.setUTC("2024 Jan 01 00:00:00")
    kepplr.setPaused(true)

    // ── Camera: looking down at Io from above ──────────
    kepplr.centerBody(io)
    kepplr.waitTransition()

    // Another interesting perspective - view in an Io-Europa synodic frame.
    // kepplr.selectBody(europa)
    // kepplr.setCameraFrame(CameraFrame.SYNODIC)

    // 2.5 million km above the system along the synodic +Z axis
    // (approximately the orbit-plane normal), giving a top-down view.
    kepplr.setCameraPosition(0, 0, 2.5e6, 5.0)
    kepplr.waitTransition()
    // Look straight down; synodic +X (toward Europa) points up in the view.
    kepplr.setCameraOrientation(0, 0, -1, 1, 0, 0, 5.0)
    kepplr.waitTransition()
    kepplr.setFov(55, 3.0)
    kepplr.waitTransition()

    // ── Trails: draw Europa and Ganymede relative to Io ───────────────────
    // setTrailReferenceBody makes each trail show pos(body) - pos(Io).
    // In the co-rotating synodic frame this reveals the closed loop geometry.
    kepplr.setTrailReferenceBody(europa, io)
    kepplr.setTrailReferenceBody(ganymede, io)

    // Trail duration = 4 Io orbital periods ≈ 611 400 s.
    // In that time Europa completes exactly 2 orbits and Ganymede exactly 1,
    // tracing the full resonance pattern.
    def fourIoPeriods = 4 * 1.769137 * 86400   // ≈ 611 400 s

    kepplr.setTrailDuration(europa, fourIoPeriods)
    kepplr.setTrailDuration(ganymede, fourIoPeriods)
    kepplr.setTrailVisible(europa, true)
    kepplr.setTrailVisible(ganymede, true)
    kepplr.setLabelVisible(io, true)
    kepplr.setLabelVisible(europa, true)
    kepplr.setLabelVisible(ganymede, true)

    // ── Run for exactly 4 Io periods ──────────────────────────────────────
    kepplr.displayMessage("Laplace resonance  Io 1 : Europa 2 : Ganymede 4")
    kepplr.setTimeRate(50000.0)
    kepplr.setPaused(false)
    kepplr.waitSim(fourIoPeriods)
    kepplr.setPaused(true)
    kepplr.displayMessage("Pattern complete: Europa 2 loops, Ganymede 1 loop", 6.0)


Toggle Overlays
---------------

A utility script that enables labels and trails for all bodies, then hides them again after a delay.

.. code-block:: groovy

   kepplr.setAllLabelsVisible(true)
   kepplr.setAllTrailsVisible(true)
   kepplr.displayMessage("All overlays ON")

   kepplr.waitWall(10.0)

   kepplr.setAllLabelsVisible(false)
   kepplr.setAllTrailsVisible(false)
   kepplr.displayMessage("All overlays OFF")


Reading Simulation State
------------------------

Query the live simulation state to make decisions in a script.

.. code-block:: groovy

   def state = kepplr.getState()
   def et = state.currentEtProperty().get()
   def rate = state.timeRateProperty().get()
   def focused = state.focusedBodyIdProperty().get()

   kepplr.displayMessage(
       "ET: ${et}  Rate: ${rate}x  Focused NAIF ID: ${focused}", 5.0
   )


Wait for a Specific Event
-------------------------

Use ``waitUntilSim()`` to pause a script until the simulation reaches a particular moment, then
take action.  This example waits for the New Horizons Pluto closest approach.

.. code-block:: groovy

    kepplr.setUTC("2015 Jul 14 07:00:00")
    kepplr.goTo("NH_SPACECRAFT", 20, 5)
    kepplr.waitTransition()
    kepplr.setSynodicFrame("NH_SPACECRAFT", "PLUTO")
    kepplr.setCameraPosition(-0.050, 0.030, 0, -98, 5)
    kepplr.waitTransition()
    // look at Pluto (+X direction in the synodic frame)
    kepplr.setCameraOrientation(1,0,0,0,0,1,5)
    kepplr.waitTransition()

    kepplr.setFrustumVisible("NH_LORRI", true)

    kepplr.setTimeRate(600.0)

    // slow down the animation just before closest approach
    kepplr.waitUntilSim("2015 Jul 14 11:30:00")
    kepplr.setTimeRate(60.0)

    // Block until closest approach
    kepplr.waitUntilSim("2015 Jul 14 11:49:57")
    kepplr.setPaused(true)
    kepplr.displayMessage("Closest approach!")
    kepplr.saveScreenshot("/tmp/nh_closest_approach.png")


Conditional Camera Adjustment
-----------------------------

Step the camera progressively closer to a body by doubling the apparent radius on each iteration
until a threshold is reached.  The second argument to ``goTo()`` is the desired apparent body
radius in degrees — a larger value places the camera closer.  The loop body moves the camera and
waits for the transition to complete before testing the condition again, so state genuinely
changes on every pass.

.. code-block:: groovy

   kepplr.goTo("Jupiter", 5.0, 3.0)
   kepplr.waitTransition()

   def apparentRadius = 5.0
   while (apparentRadius < 40.0) {
       apparentRadius *= 2.0
       kepplr.goTo("Jupiter", apparentRadius, 2.0)
       kepplr.waitTransition()
       kepplr.displayMessage("Apparent radius: ${apparentRadius.round(1)} degrees")
       kepplr.waitWall(1.0)
   }


Comparing Bodies with a Map
----------------------------

Use a Groovy map to associate bodies with display settings, then iterate over it.

.. code-block:: groovy

    // set trails about 1/4 of orbital period
    def year = 365.25 * 86400
    def bodies = [
        "Jupiter": 3 * year,
        "Saturn" : 7 * year,
        "Uranus" : 20 * year,
        "Neptune": 40 * year,
    ]

    bodies.each { name, trailDuration ->
        kepplr.setTrailDuration(name, trailDuration)
        kepplr.setTrailVisible(name, true)
        kepplr.setLabelVisible(name, true)
    }

    kepplr.centerBody("Sun")
    kepplr.setCameraPosition(0,0,2e10,5)
    kepplr.waitTransition()
    kepplr.setCameraOrientation(0,0,-1,1,0,0,5)
    kepplr.waitTransition()
    kepplr.setTimeRate(5000000.0)
    kepplr.displayMessage("Outer planet trails")


Timed Slideshow with a Closure
------------------------------

Define a reusable closure to visit a body, display it for a few seconds, and move on.
Groovy closures keep the repetitive boilerplate in one place.

.. code-block:: groovy

   def visit = { String body, double apparentRadius, double holdSeconds ->
       kepplr.goTo(body, apparentRadius, 3.0)
       kepplr.waitTransition()
       kepplr.displayMessage(body, holdSeconds)
       kepplr.waitWall(holdSeconds)
   }

   kepplr.setPaused(true)
   kepplr.setUTC("2024 Jun 21 12:00:00")
   kepplr.setRenderQuality(RenderQuality.HIGH)

   visit("Earth", 20.0, 4.0)
   visit("Mars", 18.0, 4.0)
   visit("Saturn", 12.0, 5.0)
   visit("Jupiter", 10.0, 5.0)


Stepping Through Time in a Loop
--------------------------------

Advance the simulation in discrete steps, taking a screenshot and checking a condition at each one.
This pattern is useful for searching for a geometric event.

.. This code block triggers a Pygments Groovy lexer warning.
   It is NOT a real syntax error.
   The problematic line is
           def frameNum = String.format("%04d", (int) (i / 24))

   Workarounds:
   - Use `java` instead of `groovy` for highlighting
   - Or ignore the warning

.. code-block:: java

   kepplr.goTo("Earth", 3.0, 3.0)
   kepplr.waitTransition()
   kepplr.setLabelVisible("Moon", true)

   def state = kepplr.getState()
   def stepSeconds = 3600.0       // 1-hour steps
   def steps = 24 * 30            // 30 days

   kepplr.setUTC("2024 Mar 01 00:00:00")
   kepplr.setPaused(true)

   for (int i = 0; i < steps; i++) {
       def et = state.currentEtProperty().get()
       kepplr.setET(et + stepSeconds)

       // Check every step — screenshot only on the 25th of each month at noon
       // (just an example condition; adapt to your needs)
       if (i % 24 == 0) {
           // In Groovy, / between two integers returns a BigDecimal, not an int.
           def frameNum = String.format("%04d", (int) (i / 24))
           kepplr.saveScreenshot("/tmp/earth_daily/frame_${frameNum}.png")
       }
   }

   kepplr.displayMessage("Done: ${steps} steps captured")


Switch Behavior by Camera Frame
--------------------------------

Use Groovy's ``switch`` statement to adapt script behavior based on the current camera frame.

.. code-block:: groovy

   def frame = kepplr.getState().cameraFrameProperty().get()

   switch (frame) {
       case CameraFrame.INERTIAL:
           kepplr.displayMessage("Inertial frame — orbiting will follow J2000 axes")
           kepplr.orbit(90.0, 0.0, 6.0)
           break

       case CameraFrame.BODY_FIXED:
           kepplr.displayMessage("Body-fixed frame — camera is locked to surface")
           kepplr.tilt(30.0, 3.0)
           break

       case CameraFrame.SYNODIC:
           kepplr.displayMessage("Synodic frame — co-rotating reference")
           kepplr.zoom(2.0, 3.0)
           break
   }
   kepplr.waitTransition()


Collect State Snapshots at Intervals
------------------------------------

Record a state string at regular intervals so you can return to interesting moments later.

.. code-block:: groovy

   def snapshots = []

   kepplr.goTo("Mars", 10.0, 3.0)
   kepplr.waitTransition()

   kepplr.setTimeRate(86400.0)   // 1 day per second

   5.times { i ->
       kepplr.waitSim(86400.0 * 5)   // wait 5 simulated days
       snapshots << kepplr.getStateString()
       kepplr.displayMessage("Snapshot ${i + 1} saved")
   }

   kepplr.setPaused(true)

   // Replay each saved viewpoint
   snapshots.eachWithIndex { snap, idx ->
       kepplr.setStateString(snap)
       kepplr.displayMessage("Restored snapshot ${idx + 1}", 3.0)
       kepplr.waitWall(3.0)
   }
