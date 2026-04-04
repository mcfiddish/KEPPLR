KEPPLR documentation
======================

This package contains the KEPPLR software suite version |version|.

KEPPLR is a deterministic, interactive 3D solar system simulator similar to
`Cosmographia <https://naif.jpl.nasa.gov/naif/cosmographia.html>`__.  It uses
`SPICE <https://naif.jpl.nasa.gov/naif/>`__ kernels
to render accurate positions, orientations, and lighting for solar system bodies and spacecraft.
You can fly the camera to any body in the simulation, view instrument fields of view, display orbital
trails and vector overlays, and automate sequences with Groovy scripts to produce animations.

Prerequisites
-------------

The KEPPLR package requires Java 21 or later.  Some freely available
versions are

* `Amazon Corretto <https://aws.amazon.com/corretto/>`__
* `Azul Zulu <https://www.azul.com/downloads/zulu-community/?package=jdk>`__
* `Eclipse Temurin <https://adoptium.net/>`__
* `OpenJDK <https://jdk.java.net/>`__

Download
--------

Packages for use on Mac OS X and Linux are available at ...

Windows users may use the Linux package with the `Windows Subsystem for Linux <https://docs.microsoft.com/en-us/windows/wsl/>`__.

Install
-------

::

   cd (your destination directory)
   tar xfz KEPPLR-YYYY.MM.DD-xxxxxxx.tar.gz

The `scripts` directory contains all of the applications in the
package. Running without any arguments will display a usage message.
`This <tools/index.html>`__ page shows the usage of each utility.

The `doc` directory contains a copy of the website documentation as well as `javadoc <javadoc/index.html>`__.

Build from source
-----------------

This is optional if you have a copy of the source code and want to
build your own executable packages.  You will need Java 21 and 
`Maven <https://maven.apache.org/>`__ (version 3.9.6 or higher recommended) 
installed to build the software.

From the repository
+++++++++++++++++++

The GitHub repository is at https://github.com/mcfiddish/KEPPLR.  Clone the repository and run the `mkPackage.bash`
script.

.. code-block:: console

   git clone https://github.com/mcfiddish/KEPPLR
   cd KEPPLR
   ./mkPackage.bash

This will create executable and source packages in the `dist` directory,
named KEPPLR-YYYY.MM.DD-XXXXXXX.tar.gz and KEPPLR-YYYY.MM.DD-XXXXXXX-src.tar.gz where XXXXXXX is the git hash.

From a source package
+++++++++++++++++++++

You can use a source package if you prefer.  Source packages contain all dependencies needed to compile the code.
The `mkPackage.bash` script will install the dependencies to your local maven repository.

.. code-block:: console

   tar xfz KEPPLR-YYYY.MM.DD-xxxxxxx-src.tar.gz
   cd KEPPLR-YYYY.MM.DD-src
   ./mkPackage.bash

This will create executable and source packages in the `dist` directory,
named KEPPLR-YYYY.MM.DD-UNVERSIONED.tar.gz and KEPPLR-YYYY.MM.DD-UNVERSIONED-src.tar.gz


.. toctree::
   :caption: Getting Started

   usersguide
   configuration
   gui

.. toctree::
   :caption: Scripting

   scripting
   scripting_examples

.. toctree::
   :caption: Reference

   tools/index
   python_tools
   javadoc
