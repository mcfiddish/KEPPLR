================
Groovy Scripting
================

The `Groovy <https://groovy-lang.org/>`__ scripting interface is implemented by the
`KepplrScript <file:_static/javadoc/kepplr/scripting/KepplrScript.html>`__ class.  Run a script using
`File->Run Script` from the :doc:`tools/KEPPLR` GUI.

Here's an example that generates a sequence of images simulating 8 hours every 6 seconds during New Horizon's Pluto flyby.

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


