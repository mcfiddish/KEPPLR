#!/bin/bash

# This script creates an SPK containing only the Sun, Earth, Moon, Pluto, and New Horizons.

export PATH=${HOME}/local/spice/N0067/cspice/exe/:${PATH}
lsk=/project/spice/lsk/naif0012.tls
output_spk=kepplr_test.bsp 
output_ck=kepplr_nh_test.bc
begin="14 JUL 2015 02:00:00"
end="14 JUL 2015 14:00:00"

cat << EOF > spkmerge.inp

LEAPSECONDS_KERNEL = $lsk
SPK_KERNEL         = $output_spk
SOURCE_SPK_KERNEL  = /project/spice/spk/de440s.bsp
BODIES             = 0, 10, 3, 301, 399
BEGIN_TIME         = $begin
END_TIME           = $end
SOURCE_SPK_KERNEL  = /project/newhorizons/spice/spk/nh_recon_pluto_od122_v01.bsp
BODIES             = 9, 999
BEGIN_TIME         = $begin
END_TIME           = $end
SOURCE_SPK_KERNEL  = /project/newhorizons/spice/spk/nh_recon_pluto_od122_v01.bsp
BODIES             = -98
BEGIN_TIME         = $begin
END_TIME           = $end
EOF

/bin/rm -f $output_spk

spkmerge spkmerge.inp

/bin/rm -f spkmerge.inp

brief -utc -c $output_spk $lsk

/bin/rm -f $output_ck

input_ck=/project/newhorizons/spice/ck/merged_nhpc_2015_v039.bc
sclk=/project/newhorizons/spice/sclk/new_horizons_3272.tsc
ckslicer -lsk $lsk -sclk $sclk -inputck $input_ck -outputck $output_ck -id -98000 -start $begin -stop $end

