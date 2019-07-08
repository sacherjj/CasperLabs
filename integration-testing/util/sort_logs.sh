#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

if [ "$#" -ne 1 ]; then
    echo Usage: $0 test/test_name.py
    exit 1
fi

TEST=$1

git_branch() {
    git branch | grep \* | cut -d ' ' -f2
}

remove_timestamps() {
    sed 's/[0-9][0-9]:[0-9][0-9]:[0-9][0-9]\.[0-9][0-9][0-9]//g;
         s/[0-9][0-9]:[0-9][0-9]:[0-9][0-9]//g;
         s/-.....-//g;
         s/^[ \t]*//;
         s/node-runner-[0-9][0-9]/node-runner-X/;
         s/grpc-default-executor-[0-9]/grpc-default-executor-X/;
         s/blocking-io-[0-9][0-9]/blocking-io-X/;
         s/Received Deploy .*/Received Deploy HASH/;
         s/Attempting to add Block #.*/Attempting to add Block #/;
         s/Validating the parents of .*/Validating the parents of HASH/;
         s/Computing the pre-state hash of .*/Computing the pre-state hash of HASH/;
         s/Computing the effects for .*/Computing the effects for/;
         s/Validating the transactions in .*/Validating the transactions in HASH/;
         s/Validating neglection for .*/Validating neglection for HASH/;
         s/Checking equivocation for .*/Checking equivocation for HASH/;
         s/Block effects calculated for .*/Block effects calculated for HASH/;
         s,casperlabs://.*@node,casperlabs://@node,;
         s/accepted block .*/accepted block HASH/;
         s/Tip estimates: .*/Tip estimates: HASH/;
         s/New fork-choice tip is block .*/New fork-choice tip is block HASH/;
         s/Added .*/Added HASH/;
         s/Fault tolerance for block .* is/Fault tolerance for block HASH is/;
         '
}

pushd ${DIR}/..

RAW_OUTPUT_FILE="raw_$(basename ${TEST})_$(git_branch|sed s,/,_,g)_$(date '+%Y%m%d%H%M%S').log"

./run_tests.sh ${TEST} | remove_timestamps >${RAW_OUTPUT_FILE}

OUTPUT_FILE=$(echo ${RAW_OUTPUT_FILE}|sed s/^raw_//)

echo >${OUTPUT_FILE}

for i in 0 1 2 3; do
    echo =============== NODE ${i}      >>${OUTPUT_FILE}
    grep ^node-${i} <${RAW_OUTPUT_FILE} >>${OUTPUT_FILE}
done

rm ${RAW_OUTPUT_FILE}

echo Sanitized logs saved in ${OUTPUT_FILE}
popd

