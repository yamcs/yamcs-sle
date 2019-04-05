#!/bin/sh

#This script is used to run yamcs with the sle links in a development mode
#it assumes yamcs dev is in ../yamcs

TARGET="live"
../yamcs/make-live-devel.sh $TARGET

SLE_SRC=`pwd`
ln -fs $SLE_SRC/mdb/* $TARGET/mdb
ln -fs $SLE_SRC/target/*.jar $TARGET/lib/ext
ln -fs $SLE_SRC/etc/* $TARGET/etc


cd live
bin/yamcsd
