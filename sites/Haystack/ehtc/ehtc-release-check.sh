#!/bin/bash
#
# Count the products released to catch things not shared
#
verb=${1-'false'}
[ -n "$release" ] || { echo release not defined in the environment; exit 1; }

logcount=`ls -1 $release/logs | grep -v packaging | wc -l`
$verb && echo $logcount files in $release/logs
[ "$logcount" -eq 13 ] || { echo $logcount files in $release/logs; exit 2; }

set -- `$ehtc/ehtc-joblist.py -i $dout/$evs -o *.obs -G`
while [ $# -ge 4 ]
do
    eval $2 ; eval $3 ; eval $4 ; shift 4
    $verb && echo considering proj $proj targ $targ class $class
    #
    # check for logs in the packaging area
    #
    pkg=$release/logs/$proj-$class-packaging
    $verb && ls -ld $pkg
    [ -d $pkg ] || { echo $pkg is missing ; exit 3 ; }
    job=$pkg/$proj-$targ.log
    $verb && ls -l $job
    [ -f $job ] || { echo $job is missing ; exit 4 ; }
    rel=$pkg/$proj-$targ-$class-release.log
    $verb && ls -l $rel
    [ -f $rel ] || { echo $rel is missing ; exit 5 ; }
    [ "$proj" = 'na' ] && clogs=6 || clogs=8
    lc=`ls -1 $pkg/$ers-$proj-$targ*.tar.log | wc -l`
    $verb && echo $pkg has $lc files, need $clobs for $class
    [ "$lc" -eq $clogs ] || { echo $pkg has $lc files, need $clogs; exit 6 ; }
    #
    # check for tarballs
    #
    dir=$release/$proj-$class
    $verb && ls -ld $dir
    [ -d $dir ] || { echo $dir is missing ; exit 7 ; }
    tc=`ls -1 $dir/$ers-$proj-$targ-*.tar | wc -l`
    $verb && echo $dir has $tc tarballs, need $clogs for $class
    [ "$tc" -eq $clogs ] || { echo $dir has $tc tballs, need $clogs; exit 8 ; }
done

#
# check for top-level artifacts
#
logs=$release/logs
$verb && ls -ld $logs
[ -d $logs ] || { echo $logs is missing ; exit 9 ; }
lf=$logs/$exp-$subv-v${vers}${ctry}p${iter}r${relv}.logfile
$verb && ls -l $lf
[ -f $lf ] || { echo $lf is missing ; exit 10 ; }
ls=''
for aed in amp drate sbd snr ; do ls="$ls $ers-$expn-$aed-time.pdf" ; done
for map in ant-ch-bl ant-ch bl-pol jobs ; do ls="$ls $ers-$map-map.txt" ; done
ls="$ls $ers-difxlog-clr.txt"
ls="$ls $ers-difxlog-sum.txt"
ls="$ls $ers-manifest.txt"
ls="$ls $ers-$pcal-antab.pdf"
for art in $ls
do
    rlart=$release/logs/$art
    $verb && ls -l $rlart
    [ -f $rlart ] || { echo $rlart is missing ; exit 11 ; }
done

# your final answer, please....
echo release $release seems ok with
du -sh $release | sed 's/$/ disk usage/'

#
# eof
#
