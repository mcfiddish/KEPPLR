============
User's Guide
============

Quick Start
-----------

Unpack the archive in your desired working directory:

::

    tar xfz KEPPLR-YYYY.MM.DD-xxxxxxx.tar.gz

The directory `KEPPLR-YYYY.MM.DD/scripts` contains all of the command-line utilities.  Run them without arguments to get a 
usage description.

Create a configuration file and move it to your working directory:

::

    ./KEPPLR-YYYY.MM.DD/scripts/DumpConfig tmp
    mv tmp/* .

Edit KEPPLR.config and resources/spice/kepplr.tm as needed to set correct paths.

Run KEPPLR:

::

    ./KEPPLR-YYYY.MM.DD/scripts/KEPPLR -config KEPPLR.config

See:

    * :doc:`configuration` for details on the configuration file.
    * :doc:`gui` for details on the GUI application, including the :ref:`body interaction model <body-interaction-model>` (selected, targeted, focused).
    * :doc:`scripting` for details on running a `Groovy <https://groovy-lang.org/>`__ script with KEPPLR.

.. toctree::
   :hidden:

   configuration
   gui
   scripting
