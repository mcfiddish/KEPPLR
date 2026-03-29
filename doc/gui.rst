===============
Running the GUI
===============

The main :doc:`tools/KEPPLR` launches a graphical user interface (GUI):

::

    ./KEPPLR-YYYY.MM.DD/scripts/KEPPLR -config KEPPLR.config


You'll see two windows on startup.  One is the control window and the other is the interactive display.



Control Window
--------------

.. image:: /_static/images/gui_01_control.png
    :alt: control window
    :align: center

The top panel of the control window shows

* The Focused Body: This body is the origin for the camera coordinate frame.  Focusing a body also targets and selects it.
* The Targeted Body: Point the camera to this body.  Targeting a body also selects it.
* The Selected Body: Display Information on the HUB about this body.  You can set the focused and/or targeted bodies to
  the selected body using the "Focus" and "Target" buttons.

The next panel shows

* The simulation clock
* The time rate
* the simulation clock state (Running/Paused)
* The camera frame (INERTIAL/BODY_FIXED/SYNODIC)
* The camera position in heliocentric J2000 cartesian coordinates
* The camera position in the body-fixed frame in spherical coordinates

The "Select Body" field can be used to filter the set of loaded bodies.

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
    :alt: control window
    :align: center

The "Script Console" can be used to run groovy scripting commands.  This is useful when creating or debugging scripts.
The text area below the run/clear buttons shows the result from running the command.

File
++++

.. image:: /_static/images/gui_03_file.png
   :alt: File menu
   :align: center

The File menu options are:

* Load Configuration: load a configuration.  See :doc:`configuration`
* Run Script: run a Groovy script.  See :doc:`scripting`
* Record Session: record interactions as a Groovy script.  It will have a lot of camera motion commands which you
  probably don't want, but it's a starting point for writing your own script.  A checkmark appears next to this menu
  item while a script is being recorded.  Select it again to stop recording.
* Save Screenshot: Save the displayed image as a PNG file.
* Capture Sequence: generate a sequence of PNG images with a fixed time step.
* Copy State: save a string representing the camera state to the clipboard.
* Paste State: apply a string representing the camera state from the clipboard.
* Show Log: show console output.  This is useful for debugging or reporting errors.
* Quit: exit the application

View
++++

.. image:: /_static/images/gui_04_view.png
   :alt: View menu
   :align: center

The View menu options are:

* Camera Frame: Hold the camera fixed in
    * Inertial: an inertial frame relative to the focus body
    * Body-Fixed: the focus body-fixed frame
    * Synodic: a frame with its primary axis as the direction from the focus body to the selected body, and the secondary
    axis as +Z in J2000, or +Z in Ecliptic J2000 if +X and +Z are very close together.
* Set FOV: Set the field of view in degrees.

Time
++++

.. image:: /_static/images/gui_05_time.png
   :alt: View menu
   :align: center

The Time menu options are:

* Pause/Resume: Pause or Resume the simulation clock.
* Set Time: Set the simulation clock from a UTC string.
* Set Time Rate: Set the multiplier for the simulation clock.

Overlays
++++++++

.. image:: /_static/images/gui_06_overlays.png
   :alt: View menu
   :align: center

The Overlays menu options are:

* Labels: Show labels for bodies in the display.
* HUD/Info: Show the name of the selected body and its distance from the camera in the upper left.
* Show Time: Show the simulation clock in the upper right.
* Trajectories: Plot orbit paths for all bodies in the display.
* Current Focus:
    * Sun direction: Show an arrow towards the Sun.
    * Earth direction: Show an arrow towards the Earth.
    * Velocity direction: Show an arrow showing this body's velocity relative to its primary.
    * Axes: Show this body's body fixed frame.  The arrows are Red (+X), Green (+Y), and Blue (+Z).

Instruments
+++++++++++

.. image:: /_static/images/gui_07_instruments.png
   :alt: View menu
   :align: center

Instrument frustums can be displayed for any instruments defined in the kernel pool.

Window
++++++

.. image:: /_static/images/gui_08_window.png
   :alt: View menu
   :align: center

Choose the display window size.

Display Window
--------------

.. image:: /_static/images/gui_02_display.png
   :alt: control window
   :align: center

You can use the mouse to interact with the display window.

* Clicking on a body selects it.
* Double clicking on a body focuses it.  The camera will go to the focus body.
* Left-dragging the mouse moves the camera look direction.
* Right-dragging the mouse orbits the camera about the focus body.
* The scroll wheel can be used to zoom in and out.

Keyboard bindings are:

* Left/Right arrows: roll the camera
* Shift + Left/Right arrows: orbit the camera about the focus body
* Up/Down arrows: tilt the camera
* Shift + Up/Down arrows: orbit the camera about the focus body
* PgUp/PgDn: zoom in and out
* Space: pause/unpause simulation clock
* [ / ]:  decrease/increase time rate

