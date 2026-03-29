===================
Configuration Files
===================

Run :doc:`tools/DumpConfig` to generate a sample configuration file.  This creates a configuration file and resource directory in
`tmp`.  Move them to your working directory.

::

    ./KEPPLR-YYYY.MM.DD/scripts/DumpConfig tmp
    mv tmp/* .

The configuration file is a text file in `Apache Commons Configuration <https://commons.apache.org/proper/commons-configuration>`__
format.  Here's the default version:

::

    # Configuration for KEPPLR version 2026.03.23-1bad053 built 2026-Mar-23 23:22:02 UTC
    # Created 2026-03-24 17:36:46 EDT

    ###############################################################################
    # GENERAL PARAMETERS
    ###############################################################################
    # Set the logging level.  Valid values in order of increasing detail:
    #       OFF
    #       FATAL
    #       ERROR
    #       WARN
    #       INFO
    #       DEBUG
    #       TRACE
    #       ALL
    #  See org.apache.logging.log4j.Level.
    logLevel = INFO
    # Format for log messages.  See https://logging.apache.org/log4j/2.x/manual/layouts.html#PatternLayout for
    # more details.
    logFormat = %highlight{%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level} [%c{1}:%L] %msg%n%throwable
    # Folder holding individual run outputs, to be used when running analyses on previously generated outputs.
    # If running a new simulation, leave this blank.
    outputFolder =
    # top level directory to hold outputs
    outputRoot = output
    # directory containing resources such as telecom files.
    resourcesFolder = resources
    # Format for time strings.  Allowed values are:
    # C     (e.g. 1986 APR 12 16:31:09.814)
    # D     (e.g. 1986-102 // 16:31:12.814)
    # J     (e.g. 2446533.18834276)
    # ISOC  (e.g. 1986-04-12T16:31:12.814)
    # ISOD  (e.g. 1986-102T16:31:12.814)
    timeFormat = ISOC

    ###############################################################################
    # SPICE PARAMETERS
    ###############################################################################
    # SPICE metakernel to read.  This may be specified more than once for multiple metakernels.
    spice.metakernel = resources/spice/kepplr.tm

    # NAIF ID
    body.sun.naifID = 10
    # Name.  If blank, NAIF_BODY_NAME will be used.
    body.sun.name = SUN
    # Body color as a hexadecimal number.  Ignored if textureMap is specified.
    body.sun.hexColor = #FFFF00
    # Path under resourcesFolder() for this body's texture map.  These should be in
    # simple cylindrical projection, with +90 at the top and -90 at the bottom.
    # If blank or can't be loaded, specified color will be used.
    body.sun.textureMap = maps/sun.jpg
    # Center longitude (east) of texture map in degrees.
    body.sun.centerLonDeg = 0.0
    # Path under resourcesFolder() for this body's shape model.  If blank an
    # ellipsoid model will be used
    body.sun.shapeModel =

    # NAIF ID
    body.earth.naifID = 399
    # Name.  If blank, NAIF_BODY_NAME will be used.
    body.earth.name = EARTH
    # Body color as a hexadecimal number.  Ignored if textureMap is specified.
    body.earth.hexColor = #2952FF
    # Path under resourcesFolder() for this body's texture map.  These should be in
    # simple cylindrical projection, with +90 at the top and -90 at the bottom.
    # If blank or can't be loaded, specified color will be used.
    body.earth.textureMap = maps/earth.jpg
    # Center longitude (east) of texture map in degrees.
    body.earth.centerLonDeg = 0.0
    # Path under resourcesFolder() for this body's shape model.  If blank an
    # ellipsoid model will be used
    body.earth.shapeModel =

    # NAIF ID
    body.moon.naifID = 301
    # Name.  If blank, NAIF_BODY_NAME will be used.
    body.moon.name = MOON
    # Body color as a hexadecimal number.  Ignored if textureMap is specified.
    body.moon.hexColor = #B2B2B2
    # Path under resourcesFolder() for this body's texture map.  These should be in
    # simple cylindrical projection, with +90 at the top and -90 at the bottom.
    # If blank or can't be loaded, specified color will be used.
    body.moon.textureMap = maps/moon.jpg
    # Center longitude (east) of texture map in degrees.
    body.moon.centerLonDeg = 0.0
    # Path under resourcesFolder() for this body's shape model.  If blank an
    # ellipsoid model will be used
    body.moon.shapeModel =

    ###############################################################################
    # SPACECRAFT PARAMETERS
    ###############################################################################
    # NAIF_BODY_CODE
    spacecraft.newhorizons.naifID = -98
    # Display name.  If blank, NAIF_BODY_NAME will be used.
    spacecraft.newhorizons.name = New Horizons
    # Spacecraft frame.  If blank, will be taken from OBJECT_<name or spk_id>_FRAME in kernel pool.
    spacecraft.newhorizons.frame = NH_SPACECRAFT
    # Path under resourcesFolder() for this body's shape model.  If blank or missing
    # body will not be drawn.  Units are assumed to be meters.
    spacecraft.newhorizons.shapeModel = shapes/new_horizons_dds.glb
    # Scale factor for spacecraft shape model.  1.0 keeps the size in meters.
    spacecraft.newhorizons.scale = 1.0


Any object with an ephemeris will be drawn.  If an object does not have a body block specified, the following assumptions are used:

 * its name is the one bound to its NAIF ID.
 * its color is white (#FFFFFF)
 * its texture map is assumed to exist in resources/maps/<name>.jpg where name is lowercase.  If no such file exists, no texture map is applied.
 * its shape is assumed to be an ellipsoid.  If no ellipsoid is defined in the kernel pool, it will be drawn as a point.

You may need to either change

::

    spice.metakernel = resources/spice/kepplr.tm

to point to another SPICE kernel, or edit the `PATH_VALUES` line in `resources/spice/kepplr.tm` to point to the location
of your SPICE files.  The script `getSPK.bash` in `resources/spice/spk` can be used to download some useful solar system
kernels from NAIF.

If you want to specify texture maps or shape models for other bodies, add them here.  For example, this will draw
Phobos as an ellipsoid with a texture map.

::

        # NAIF ID
        body.phobos.naifID = 401
        # Name.  If blank, NAIF_BODY_NAME will be used.
        body.phobos.name =
        # Body color as a hexadecimal number.  Ignored if textureMap is specified.
        body.phobos.hexColor = #B2B2B2
        # Path under resourcesFolder() for this body's texture map.  If blank specified color will be used.
        body.phobos.textureMap = maps/phobos.jpg
        # Center longitude (east) of texture map in degrees.
        body.phobos.centerLonDeg = 0.0
        # Path under resourcesFolder() for this body's shape model.  If blank an ellipsoid model will be used
        body.phobos.shapeModel =

See :doc:`pythonindex` for instructions on generating shape model files.