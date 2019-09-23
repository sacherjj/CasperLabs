#!/usr/bin/env bash

set -e

RED='\033[0;31m'
CYAN='\033[0;36m'
NO_COLOR='\033[0m'
EE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." >/dev/null 2>&1 && pwd)"
BASE_TEMP_DIR="/tmp/simple-transfer-perf"
THIS_RUN_TEMP_DIR="${BASE_TEMP_DIR}/$RANDOM"

check_for_perf() {
  if ! [[ -x "$(command -v perf)" ]]; then
    printf "${RED}perf not installed${NO_COLOR}\n\n"
    printf "For Debian, try:\n"
    printf "${CYAN}sudo apt install linux-tools-common linux-tools-generic linux-tools-$(uname -r)${NO_COLOR}\n\n"
    printf "For Redhat, try:\n"
    printf "${CYAN}sudo yum install perf${NO_COLOR}\n\n"
    exit 127
  fi
}

run_perf() {
  cd $EE_DIR
  make build-contracts
  cd engine-tests/
  cargo build --release --bin state-initializer
  cargo build --release --bin simple-transfer
  ../target/release/state-initializer --data-dir=../target | perf record -g --call-graph dwarf ../target/release/simple-transfer --data-dir=../target
  mkdir -p ${THIS_RUN_TEMP_DIR}
  mv perf.data ${THIS_RUN_TEMP_DIR}
}

check_or_clone_flamegraph() {
  FLAMEGRAPH_DIR="${BASE_TEMP_DIR}/Flamegraph"
  export PATH=${FLAMEGRAPH_DIR}:${PATH}
  if ! [[ -x "$(command -v stackcollapse-perf.pl)" ]] || ! [[ -x "$(command -v flamegraph.pl)" ]]; then
    rm -rf ${FLAMEGRAPH_DIR}
    git clone https://github.com/brendangregg/FlameGraph ${FLAMEGRAPH_DIR}
  fi
}

create_flamegraph() {
  FLAMEGRAPH="${THIS_RUN_TEMP_DIR}/flame.svg"
  printf "Creating flamegraph at ${FLAMEGRAPH}\n"
  cd ${THIS_RUN_TEMP_DIR}
  perf script | stackcollapse-perf.pl | flamegraph.pl > ${FLAMEGRAPH}
  x-www-browser ${FLAMEGRAPH}
}

check_for_perf
run_perf
check_or_clone_flamegraph
create_flamegraph
