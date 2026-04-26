============
User's Guide
============

Quick Start
-----------

Unpack the archive in your desired working directory:

::

    tar xfz KEPPLR-YYYY.MM.DD-xxxxxxx.tar.gz

The directory `KEPPLR-YYYY.MM.DD/scripts` contains all of the command-line utilities.  Run them without arguments to
get a usage description.

Create a configuration file and move it to your working directory:

::

    ./KEPPLR-YYYY.MM.DD/scripts/DumpConfig tmp
    mv tmp/* .

You can run the script `resources/spice/spk/getSPK.bash` to download SPICE kernels containing ephemerides for most
planets and satellites from NAIF.

Edit KEPPLR.config and resources/spice/kepplr.tm as needed to set correct paths.

Run KEPPLR:

::

    ./KEPPLR-YYYY.MM.DD/scripts/KEPPLR -config KEPPLR.config

Scene Presets
-------------

KEPPLR supports saving and loading the complete visual state to a `.kepplrscene` JSON file. This includes:

- **Time**: Simulation epoch (ET), time rate, and paused state
- **Camera**: Position, orientation, frame, and field of view
- **Bodies**: Focused, targeted, and selected body; per-body visibility
- **Overlays**: Labels, trails, trail durations, trail references, vectors, frustums, and HUD visibility
- **Render**: Quality preset
- **Window**: Window size (width and height)

Using the UI
~~~~~~~~~~~~

From the **File** menu, use **Save Scene...** to save the current scene to a `.kepplrscene` file, and **Load Scene...** to restore a previously saved scene.

When loading an invalid scene file, KEPPLR displays an error dialog and preserves the current application state unchanged (atomic load behavior).

Using Scripts
~~~~~~~~~~~~

From a Groovy script, use:

.. code-block:: groovy

    // Save the current scene
    kepplr.saveScene("/path/to/scene.kepplrscene")

    // Load a scene (restores all visual state)
    kepplr.loadScene("/path/to/scene.kepplrscene")

The load operation validates all fields before applying any state. If validation fails, an ``IllegalArgumentException`` is thrown and the current scene remains unchanged.

File Format
~~~~~~~~~~~

The `.kepplrscene` format is versioned JSON. The current version is 1. Example:

.. code-block:: json

    {
      "version": 1,
      "time": { "et": 421348864.184, "timeRate": 1.0, "paused": false },
      "camera": {
        "position": [1.234e8, -5.678e7, 9.012e6],
        "orientation": [0.0, 0.0, 0.0, 1.0],
        "frame": "INERTIAL",
        "fovDeg": 45.0
      },
      "bodies": {
        "focused": 399,
        "targeted": 301,
        "selected": 399,
        "visibility": { "399": true, "301": true }
      },
      "render": { "quality": "HIGH" },
      "window": { "width": 1920, "height": 1080 },
      "overlays": {
        "labels": { "399": true },
        "trails": { "399": true },
        "trailDurations": { "399": -1.0 },
        "trailReferences": { "399": -1 },
        "vectors": { "399:velocity": true },
        "frustums": { "-98300": true },
        "hud": { "time": true, "info": true }
      }
    }

Unknown fields are preserved when loading older scene files, ensuring forward compatibility.

Backward Compatibility
~~~~~~~~~~~~~~~~~~~~~~

The compact state string (from **Edit → Copy State** / **Edit → Paste State**) remains supported as an alternative for quick bookmarks. It uses a binary Base64url format and does not include overlays or window size.

See:

    * :doc:`configuration` for details on the configuration file.
    * :doc:`gui` for details on the GUI application, including the :ref:`body interaction model <body-interaction-model>` (selected, targeted, focused).
    * :doc:`scripting` for details on running a `Groovy <https://groovy-lang.org/>`__ script with KEPPLR.

.. toctree::
   :hidden:

   configuration
   gui
   scripting
