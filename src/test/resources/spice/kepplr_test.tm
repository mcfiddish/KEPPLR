KPL/MK

Kernels for unit tests.  This covers the time range 2015 JUL 14 02:00 
to 2015 JUL 14 08:00 (New Horizons Pluto encounter)

\begindata

PATH_SYMBOLS = ( 'SPICE' ) 
PATH_VALUES = ( 'src/test/resources/spice' )

KERNELS_TO_LOAD = (
'$SPICE/ck/kepplr_nh_test.bc'
'$SPICE/fk/nh_v220.tf'
'$SPICE/lsk/naif0012.tls'
'$SPICE/pck/pck00011.tpc'
'$SPICE/sclk/new_horizons_3272.tsc'
'$SPICE/spk/kepplr_test.bsp'
)
