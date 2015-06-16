#!/bin/bash
IN=$1
OUT=${IN/%.*/.png}

dot -Tpng $IN > $OUT
exit 0
