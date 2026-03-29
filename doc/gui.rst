===============
Running the GUI
===============

The main :doc:`tools/KEPPLR` launches a graphical user interface (GUI):

::

    ./KEPPLR-YYYY.MM.DD/scripts/KEPPLR -config KEPPLR.config


You'll see two windows on startup.  One is the control window and the other is the interactive display.


.. _body-interaction-model:

Body Interaction Model
----------------------

KEPPLR uses three levels of body interaction.  Each level includes the effects of the levels below it:

**Selected**
    The selected body's name and distance from the camera are shown on the HUD.  Selecting a body does not
    move the camera.  Click on a body in the display or single-click in the body list to select it.

**Targeted**
    The camera points at the targeted body.  Targeting a body also selects it.

**Focused**
    The focused body becomes the origin of the camera coordinate frame.  The camera moves to the focused body
    and orbits around it.  Focusing a body also targets and selects it.  Double-click a body in the display or
    in the body list to focus it.

For example, you might focus on Earth, then target the Moon so the camera stays at Earth but points toward
the Moon.  You could then select a spacecraft to see its distance on the HUD without changing the camera.


Control Window
--------------

.. image:: /_static/images/gui_01_control.png
    :alt: control window
    :align: center

The top panel of the control window shows the currently focused, targeted, and selected bodies.
You can set the focused and/or targeted bodies to the selected body using the "Focus" and "Target" buttons.

The next panel shows

* The simulation clock (UTC)
* The time rate -- a multiplier for the simulation clock.  A rate of 1 means one simulated second per
  wall-clock second.  A rate of 3600 means one simulated hour passes per wall-clock second.
* The simulation clock state (Running/Paused)
* The camera frame (INERTIAL/BODY_FIXED/SYNODIC)
* The camera position in heliocentric J2000 cartesian coordinates
* The camera position in the body-fixed frame in spherical coordinates

The "Select Body" field can be used to filter the set of loaded bodies by name or NAIF ID.

The body panel shows the set of loaded bodies, sorted by distance from the Sun.

* Single click on a body sets it as selected.
* Double click on a body sets it as focused, targeted, and selected.
* Right click on a body brings up a context menu

    * Focus sets this as the focused body
    * Target sets this as the targeted body
    * Trail shows/hides the orbit trail
    * Label shows/hides the body label
    * Axes shows/hides the body-fixed frame axes
    * Visible toggles this body's visibility

.. image:: /_static/images/gui_01a_context.png
    :alt: body context menu
    :align: center

The "Script Console" can be used to run Groovy scripting commands interactively.  This is useful when
creating or debugging scripts.  The text area below the run/clear buttons shows the output from the command.
See :doc:`scripting` for the scripting API.

File
++++

.. image:: /_static/images/gui_03_file.png
   :alt: File menu
   :align: center

The File menu options are:

* Load Configuration: load a configuration file.  This replaces the current set of loaded bodies, kernels,
  and instruments.  See :doc:`configuration`.
* Run Script: run a Groovy script.  See :doc:`scripting`.
* Record Session: record interactions as a Groovy script.  It will have a lot of camera motion commands which
  you probably don't want, but it's a starting point for writing your own script.  A checkmark appears next to
  this menu item while a script is being recorded.  Select it again to stop recording.
* Save Screenshot: save the displayed image as a PNG file.
* Capture Sequence: generate a sequence of PNG images with a fixed time step and frame count.  This is useful
  for creating animations -- the resulting image sequence can be assembled into a video using tools like FFmpeg.
* Copy State: copy a string representing the full simulation state (camera position, orientation, time, focused
  body, etc.) to the clipboard.  Useful for bookmarking a view.
* Paste State: restore simulation state from a previously copied state string.
* Show Log: show console output.  This is useful for debugging or reporting errors.
* Quit: exit the application.

View
++++

.. image:: /_static/images/gui_04_view.png
   :alt: View menu
   :align: center

The View menu options are:

* Camera Frame: select the reference frame for the camera.

    * Inertial: the camera holds its orientation relative to the stars.  As the focused body rotates, its
      surface appears to spin beneath the camera.
    * Body-Fixed: the camera rotates with the focused body.  Surface features stay in place and the stars
      rotate overhead.  Falls back to Inertial if the focused body has no orientation data.
    * Synodic: the camera is held in a rotating frame whose +X axis points from the focused body toward the
      selected body.  This keeps the selected body fixed on screen and is useful for visualizing relative
      geometry (e.g., a spacecraft approach trajectory).  The secondary axis is +Z in J2000, or +Z in
      Ecliptic J2000 if +X and +Z are nearly aligned.

* Set FOV: set the camera field of view in degrees.

Time
++++

.. image:: /_static/images/gui_05_time.png
   :alt: Time menu
   :align: center

The Time menu options are:

* Pause/Resume: pause or resume the simulation clock.
* Set Time: set the simulation clock from a UTC string (e.g., ``2015 Jul 14 08:00:00``).
* Set Time Rate: set the multiplier for the simulation clock.  For example, a rate of 60 means
  one simulated minute passes per wall-clock second.

Overlays
++++++++

.. image:: /_static/images/gui_06_overlays.png
   :alt: Overlays menu
   :align: center

The Overlays menu options are:

* Labels: show labels for bodies in the display.
* HUD/Info: show the name of the selected body and its distance from the camera in the upper left.
* Show Time: show the simulation clock (UTC) in the upper right.
* Trajectories: show orbit trails for all bodies in the display.
* Current Focus: vector overlays attached to the currently focused body.

    * Sun Direction: show an arrow from the focused body toward the Sun.
    * Earth Direction: show an arrow from the focused body toward Earth.
    * Velocity Direction: show an arrow in the direction of the focused body's orbital velocity relative to
      its parent body.  For satellites this is relative to the system barycenter; for planets it is
      heliocentric.  The arrow points along the orbital trail into the future.
    * Trajectory: show the orbit trail for the focused body.
    * Axes: show the body-fixed frame axes.  The arrows are Red (+X), Green (+Y), and Blue (+Z).

Instruments
+++++++++++

.. image:: /_static/images/gui_07_instruments.png
   :alt: Instruments menu
   :align: center

Instrument field-of-view frustums can be displayed for any instruments defined in the kernel pool.
Instruments are configured by the SPICE IK (Instrument Kernel) and are associated with a spacecraft.

Window
++++++

.. image:: /_static/images/gui_08_window.png
   :alt: Window menu
   :align: center

Choose the display window size.  Preset options are 1280x720, 1280x1024, 1920x1080, and 2560x1440.

Display Window
--------------

.. image:: /_static/images/gui_02_display.png
    :alt: display window
    :align: center

You can use the mouse to interact with the display window.

* Clicking on a body selects it.
* Double clicking on a body focuses it.  The camera will slew to the focused body.
* Left-dragging the mouse rotates the camera look direction (free-look).
* Right-dragging the mouse orbits the camera around the focused body.
* The scroll wheel zooms in and out.

Keyboard Controls
+++++++++++++++++

* Up/Down arrows: tilt the camera up and down
* Left/Right arrows: roll the camera
* Shift + Up/Down arrows: orbit the camera vertically around the focused body
* Shift + Left/Right arrows: orbit the camera horizontally around the focused body
* PgUp/PgDn: zoom in and out
* Space: pause/resume the simulation clock
* ``[`` / ``]``: decrease/increase time rate by a factor of 10
