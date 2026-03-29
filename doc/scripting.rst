================
Groovy Scripting
================

KEPPLR can be automated using `Groovy <https://groovy-lang.org/>`__ scripts.  Scripts have access to a
``kepplr`` object (an instance of
`KepplrScript <file:_static/javadoc/kepplr/scripting/KepplrScript.html>`__) that provides methods for
controlling the simulation, camera, and overlays.

Run a script using ``File > Run Script`` from the GUI, or enter commands directly in the Script Console
at the bottom of the control window.  See :doc:`gui` for details.


Basic Concepts
--------------

**Blocking vs. non-blocking methods.**
Most methods that move the camera (``goTo``, ``pointAt``, ``orbit``, ``setCameraPosition``, etc.) start
an animated transition and return immediately.  Use ``waitTransition()`` to pause the script until the
transition completes.  Methods that change state instantly (``selectBody``, ``setPaused``, ``setTimeRate``)
take effect immediately.

**Identifying bodies.**
Methods that take a body accept either a NAIF ID (integer) or a body name (string).  For example,
``kepplr.focusBody(399)`` and ``kepplr.focusBody("Earth")`` are equivalent.

**Coordinate frames.**
Camera position and orientation methods (``setCameraPosition``, ``setCameraOrientation``) interpret their
arguments in the active camera frame (INERTIAL, BODY_FIXED, or SYNODIC).  Set the frame with
``setCameraFrame()`` before positioning the camera.

**Units.**
Distances are in kilometers.  Angles are in degrees.  Times are in seconds (simulation or wall-clock,
depending on the method).


Example
-------

This script simulates 8 hours of New Horizon's Pluto flyby, capturing an image every 6 seconds of
simulated time:

::

    kepplr.setUTC("2015 Jul 14 07")
    kepplr.loadConfiguration("/Users/hari/Project/KEPPLR/nh.config")

    kepplr.displayMessage("Go to New Horizons", 5)
    kepplr.focusBody(-98)
    kepplr.waitTransition()

    // set up a synodic frame with New Horizons -> Pluto as the primary axis
    kepplr.selectBody(999)
    kepplr.waitWall(2)
    kepplr.setCameraFrame(CameraFrame.SYNODIC)
    kepplr.waitWall(2)

    kepplr.displayMessage("Show instrument frustums", 5)
    kepplr.setFrustumVisible("NH_LORRI", true)
    kepplr.setFrustumVisible("NH_RALPH_MVIC_BLUE", true)
    kepplr.waitWall(2)

    // hide the barycenter
    kepplr.setBodyVisible(9, false)

    // show labels for bodies in the Pluto system
    kepplr.setLabelVisible(905, true)
    kepplr.setLabelVisible(999, true)
    kepplr.setLabelVisible(903, true)
    kepplr.setLabelVisible(901, true)
    kepplr.setLabelVisible(904, true)
    kepplr.setLabelVisible(902, true)
    kepplr.waitWall(2)
    kepplr.setPaused(true)

    // Synodic frame, +X is NH -> Pluto direction
    // place camera 50 m behind, then move 30 m to the "right" if J2000 +Z points up
    kepplr.displayMessage("Move camera", 5)
    kepplr.setCameraPosition(-0.050, 0.030, 0, -98, 5)
    kepplr.waitTransition()
    // look at Pluto (+X direction in the synodic frame)
    kepplr.setCameraOrientation(1,0,0,0,0,1,5)
    kepplr.waitTransition()

    // turn the camera and resize window
    kepplr.yaw(-10, 5)
    kepplr.waitTransition()
    kepplr.setFov(30, 5)
    kepplr.setWindowSize(1920, 1080)
    kepplr.waitTransition()

    // start capturing images for an animation
    kepplr.captureSequence("/Users/hari/Desktop/KEPPLR/", "2015 Jul 14 07:00:00", 4800, 6)


API Reference
-------------

The following sections list the methods available on the ``kepplr`` object.  All methods that accept a
body name also have an overload that accepts a NAIF ID (integer).


Body Selection
++++++++++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``selectBody(body)``
     - Set the selected body.  Does not move the camera.
   * - ``targetBody(body)``
     - Point the camera at the given body.  Also selects it.
   * - ``focusBody(body)``
     - Move the camera to orbit the given body.  Also targets and selects it.


Time Control
++++++++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``setPaused(boolean)``
     - Pause or resume the simulation clock.
   * - ``setTimeRate(rate)``
     - Set the time rate (simulated seconds per wall-clock second).
   * - ``setET(et)``
     - Set the simulation time as TDB seconds past J2000.
   * - ``setUTC(utcString)``
     - Set the simulation time from a UTC string (e.g., ``"2015 Jul 14 08:00:00"``).


Camera Motion
+++++++++++++

These methods start animated transitions.  Use ``waitTransition()`` to block until the transition completes.

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``goTo(body, apparentRadiusDeg, durationSec)``
     - Move the camera so that the body fills the given apparent radius in degrees.
   * - ``pointAt(body, durationSec)``
     - Slew the camera to point at the given body.
   * - ``zoom(factor, durationSec)``
     - Zoom by the given factor (>1 zooms in, <1 zooms out).
   * - ``setFov(degrees, durationSec)``
     - Animate the field of view to the given value in degrees.
   * - ``orbit(rightDeg, upDeg, durationSec)``
     - Orbit the camera around the focused body by the given angles.
   * - ``tilt(degrees, durationSec)``
     - Tilt the camera up (positive) or down (negative).
   * - ``yaw(degrees, durationSec)``
     - Yaw the camera left (positive) or right (negative).
   * - ``roll(degrees, durationSec)``
     - Roll the camera.
   * - ``truck(km, durationSec)``
     - Move the camera left/right by the given distance.  Also available without duration (instant).
   * - ``crane(km, durationSec)``
     - Move the camera up/down by the given distance.  Also available without duration (instant).
   * - ``dolly(km, durationSec)``
     - Move the camera forward/backward by the given distance.  Also available without duration (instant).
   * - ``setCameraPosition(x, y, z, durationSec)``
     - Move the camera to the given position in the active frame.  An overload accepts an origin body
       to specify the position relative to a body other than the focused body.
   * - ``setCameraOrientation(lookX, lookY, lookZ, upX, upY, upZ, durationSec)``
     - Set the camera look and up directions in the active frame.
   * - ``cancelTransition()``
     - Cancel any in-progress camera transition.


Camera Frame
++++++++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``setCameraFrame(frame)``
     - Set the camera frame.  Use ``CameraFrame.INERTIAL``, ``CameraFrame.BODY_FIXED``, or
       ``CameraFrame.SYNODIC``.
   * - ``setSynodicFrame(focusBody, targetBody)``
     - Set up a synodic frame between two bodies and switch to it.


Overlays and Visibility
+++++++++++++++++++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``setLabelVisible(body, boolean)``
     - Show or hide the label for a body.
   * - ``setAllLabelsVisible(boolean)``
     - Show or hide all labels.
   * - ``setTrailVisible(body, boolean)``
     - Show or hide the orbit trail for a body.
   * - ``setAllTrailsVisible(boolean)``
     - Show or hide all orbit trails.
   * - ``setTrailDuration(body, seconds)``
     - Set the duration of a body's orbit trail in seconds.
   * - ``setVectorVisible(body, vectorType, boolean)``
     - Show or hide a vector overlay.  Vector types include ``VectorTypes.velocity()``,
       ``VectorTypes.towardBody(naifId)``, ``VectorTypes.bodyAxisX()``, etc.
   * - ``setBodyFixedAxesVisible(body, boolean)``
     - Show or hide all three body-fixed axes (X, Y, Z) at once.
   * - ``setFrustumVisible(instrument, boolean)``
     - Show or hide an instrument frustum.  Accepts a NAIF code or instrument name.
   * - ``setBodyVisible(body, boolean)``
     - Show or hide a body entirely.
   * - ``setHudTimeVisible(boolean)``
     - Show or hide the UTC time display.
   * - ``setHudInfoVisible(boolean)``
     - Show or hide the selected-body info display.
   * - ``setRenderQuality(quality)``
     - Set the render quality.  Use ``RenderQuality.LOW``, ``RenderQuality.MEDIUM``, or
       ``RenderQuality.HIGH``.


Output
++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``saveScreenshot(path)``
     - Save the current display as a PNG file.
   * - ``captureSequence(outputDir, startET, frameCount, etStep)``
     - Capture a sequence of frames as PNG images.  An overload accepts a UTC string for the start time.
   * - ``setWindowSize(width, height)``
     - Resize the display window.
   * - ``displayMessage(text)``
     - Display a message on the HUD.  An overload accepts a duration in seconds.


State
+++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``getStateString()``
     - Return a string encoding the full simulation state (camera, time, focused body, etc.).
   * - ``setStateString(stateString)``
     - Restore simulation state from a previously saved state string.
   * - ``loadConfiguration(path)``
     - Load a new configuration file, replacing the current bodies and kernels.


Waiting
+++++++

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Method
     - Description
   * - ``waitTransition()``
     - Block until any in-progress camera transition completes.
   * - ``waitWall(seconds)``
     - Pause the script for the given number of wall-clock seconds.
   * - ``waitSim(seconds)``
     - Pause the script until the given number of simulated seconds have elapsed.
   * - ``waitUntilSim(targetET)``
     - Pause the script until the simulation reaches the given ET.  An overload accepts a UTC string.
