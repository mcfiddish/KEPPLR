==================
Scripting Examples
==================

This page provides ready-to-use Groovy scripts for common KEPPLR tasks.  Copy any example into
``File > Run Script`` or paste it into the Script Console.  For the full API reference, see
:doc:`scripting`.


Tour of the Inner Solar System
------------------------------

Fly the camera to each of the inner planets in sequence.

.. code-block:: groovy

   kepplr.setUTC("2024 Jan 01 00:00:00")
   kepplr.setTimeRate(1.0)

   ["Mercury", "Venus", "Earth", "Mars"].each { body ->
       kepplr.focusBody(body)
       kepplr.waitTransition()
       kepplr.goTo(body, 15.0, 4.0)
       kepplr.waitTransition()
       kepplr.displayMessage(body, 3.0)
       kepplr.waitWall(3.0)
   }


Watch a Full Orbit
------------------

Focus on a body, enable its trail, and fast-forward through one orbital period.

.. code-block:: groovy

   kepplr.focusBody("Mars")
   kepplr.waitTransition()
   kepplr.goTo("Mars", 5.0, 3.0)
   kepplr.waitTransition()

   kepplr.setTrailVisible("Mars", true)
   kepplr.setTrailDuration("Mars", 59356800.0)   // ~687 days in seconds

   kepplr.setTimeRate(500000.0)
   kepplr.waitSim(59356800.0)
   kepplr.setPaused(true)


Earth-Moon System Close-Up
--------------------------

Zoom in on the Earth and watch the Moon orbit.

.. code-block:: groovy

   kepplr.setUTC("2024 Mar 25 00:00:00")

   kepplr.focusBody("Earth")
   kepplr.waitTransition()
   kepplr.goTo("Earth", 3.0, 4.0)
   kepplr.waitTransition()

   kepplr.setTrailVisible("Moon", true)
   kepplr.setTrailDuration("Moon", 2360592.0)   // ~27.3 days

   kepplr.setLabelVisible("Moon", true)
   kepplr.setTimeRate(50000.0)
   kepplr.waitSim(2360592.0)
   kepplr.setPaused(true)


Capture a Screenshot Sequence
-----------------------------

Generate a series of frames suitable for encoding into a video.  Each frame advances the simulation
by a fixed time step.

.. code-block:: groovy

   kepplr.setWindowSize(1920, 1080)

   kepplr.focusBody("Jupiter")
   kepplr.waitTransition()
   kepplr.goTo("Jupiter", 12.0, 3.0)
   kepplr.waitTransition()

   // 600 frames, 1 hour per frame = 25 days of Jupiter rotation
   kepplr.captureSequence("/tmp/jupiter_frames/", "2024 Jun 01 00:00:00", 600, 3600.0)


Orbit Around a Body
--------------------

Orbit the camera 360 degrees around Saturn for a cinematic sweep.

.. code-block:: groovy

   kepplr.focusBody("Saturn")
   kepplr.waitTransition()
   kepplr.goTo("Saturn", 10.0, 5.0)
   kepplr.waitTransition()
   kepplr.setPaused(true)

   // Full orbit in 36 steps of 10 degrees
   36.times {
       kepplr.orbit(10.0, 0.0, 0.5)
       kepplr.waitTransition()
   }


Show Velocity Vectors
---------------------

Display velocity vectors for the inner planets to visualize orbital motion.

.. code-block:: groovy

   kepplr.focusBody("Sun")
   kepplr.waitTransition()
   kepplr.goTo("Sun", 0.5, 5.0)
   kepplr.waitTransition()

   ["Mercury", "Venus", "Earth", "Mars"].each { body ->
       kepplr.setTrailVisible(body, true)
       kepplr.setLabelVisible(body, true)
       kepplr.setVectorVisible(body, VectorTypes.velocity(), true)
   }

   kepplr.setTimeRate(1000000.0)


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

   kepplr.focusBody("Earth")
   kepplr.waitTransition()
   kepplr.goTo("Earth", 20.0, 4.0)
   kepplr.waitTransition()

   kepplr.setCameraFrame(CameraFrame.BODY_FIXED)
   kepplr.setTimeRate(3600.0)
   kepplr.displayMessage("Body-fixed frame: Earth rotates beneath the camera")


Synodic Frame
-------------

View the Earth-Moon system from a synodic (co-rotating) frame where both bodies appear stationary.

.. code-block:: groovy

   kepplr.focusBody("Earth")
   kepplr.waitTransition()
   kepplr.goTo("Earth", 1.0, 4.0)
   kepplr.waitTransition()

   kepplr.setSynodicFrame("Earth", "Moon")
   kepplr.setLabelVisible("Moon", true)
   kepplr.setTimeRate(100000.0)
   kepplr.displayMessage("Synodic frame: Earth-Moon system co-rotating")


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

   kepplr.focusBody("Pluto")
   kepplr.waitTransition()
   kepplr.goTo("Pluto", 8.0, 4.0)
   kepplr.waitTransition()

   kepplr.setLabelVisible("Pluto", true)
   kepplr.setFrustumVisible("NH_LORRI", true)
   kepplr.setTrailVisible(-98, true)

   kepplr.setUTC("2015 Jul 14 07:00:00")
   kepplr.setTimeRate(60.0)

   // Block until closest approach
   kepplr.waitUntilSim("2015 Jul 14 11:49:57")
   kepplr.setPaused(true)
   kepplr.displayMessage("Closest approach!")
   kepplr.saveScreenshot("/tmp/nh_closest_approach.png")


Conditional Camera Adjustment
-----------------------------

Read the simulation state inside a loop and react when a condition is met.  Here we zoom in
progressively as the camera gets closer to the focused body.

.. code-block:: groovy

   kepplr.focusBody("Jupiter")
   kepplr.waitTransition()
   kepplr.goTo("Jupiter", 5.0, 4.0)
   kepplr.waitTransition()

   def state = kepplr.getState()
   def fov = state.fovDegProperty().get()

   if (fov > 30.0) {
       kepplr.setFov(30.0, 2.0)
       kepplr.waitTransition()
       kepplr.displayMessage("Narrowed FOV to 30 degrees")
   } else if (fov > 10.0) {
       kepplr.setFov(10.0, 2.0)
       kepplr.waitTransition()
       kepplr.displayMessage("Narrowed FOV to 10 degrees")
   } else {
       kepplr.displayMessage("FOV already narrow: ${fov} degrees")
   }


Comparing Bodies with a Map
----------------------------

Use a Groovy map to associate bodies with display settings, then iterate over it.

.. code-block:: groovy

   def bodies = [
       "Jupiter": 30 * 86400.0,     // 30-day trail
       "Saturn" : 60 * 86400.0,     // 60-day trail
       "Uranus" : 180 * 86400.0,    // 180-day trail
       "Neptune": 365 * 86400.0,    // 365-day trail
   ]

   bodies.each { name, trailDuration ->
       kepplr.setTrailVisible(name, true)
       kepplr.setTrailDuration(name, trailDuration)
       kepplr.setLabelVisible(name, true)
   }

   kepplr.focusBody("Sun")
   kepplr.waitTransition()
   kepplr.goTo("Sun", 0.1, 5.0)
   kepplr.waitTransition()
   kepplr.setTimeRate(5000000.0)
   kepplr.displayMessage("Outer planet trails")


Timed Slideshow with a Closure
------------------------------

Define a reusable closure to visit a body, display it for a few seconds, and move on.
Groovy closures keep the repetitive boilerplate in one place.

.. code-block:: groovy

   def visit = { String body, double apparentRadius, double holdSeconds ->
       kepplr.focusBody(body)
       kepplr.waitTransition()
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

.. code-block:: groovy

   kepplr.focusBody("Earth")
   kepplr.waitTransition()
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
           def frameNum = String.format("%04d", i / 24)
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

   kepplr.focusBody("Mars")
   kepplr.waitTransition()
   kepplr.goTo("Mars", 10.0, 3.0)
   kepplr.waitTransition()

   kepplr.setTimeRate(86400.0)   // 1 day per second

   5.times { i ->
       kepplr.waitSim(86400.0 * 30)   // wait 30 simulated days
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
