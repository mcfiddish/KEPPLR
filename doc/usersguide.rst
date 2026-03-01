User's Guide
============

Unpack the archive in your desired working directory:

::

    tar xfz KEPPLR-YYYY.MM.DD-xxxxxxx.tar.gz

The directory `KEPPLR-YYYY.MM.DD/scripts` contains all of the command-line utilities.  Run them without arguments to get a 
usage description.

Run `DumpConfig` to generate a sample configuration file.  This creates a configuration file and resource directory in 
`tmp`.  Move them to your working directory.

::

    ./KEPPLR-YYYY.MM.DD/scripts/DumpConfig tmp
    mv tmp/* .

The configuration file is a text file in `Apache Commons Configuration <https://commons.apache.org/proper/commons-configuration>`__
format.  Change the `spice.metakernel` to your existing metakernel(s).  For example:

::

    # SPICE metakernel to read.  This may be specified more than once for multiple metakernels.
    spice.metakernel = /project/spice/voyager.tm


