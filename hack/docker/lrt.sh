#!/usr/bin/env bash

# Runs a local LRT:
# 3 nodes continuously send motes to each other in parallel.
# Sending deploys without proposes, expects auto proposer to be enabled

# Explorer available at localhost:8919

# Next commands expected to be run before from the root project dir
# make clean
# make all

set -euo pipefail
# Kills background processes on exit
# https://spin.atomicobject.com/2017/08/24/start-stop-bash-background-process/
trap "exit" INT TERM ERR
trap "kill 0" EXIT

make clean
CL_CASPER_AUTO_PROPOSE_ENABLED=true make up node-0/up node-1/up node-2/up

save_logs() {
  NODE_INDEX="$1"
  docker logs -f node-"$NODE_INDEX" > node-"$NODE_INDEX".log
}

for NODE_INDEX in {0..2}; do
  save_logs "$NODE_INDEX" &
done

sleep 10

initialize_accounts() {
  echo "Initialising accounts"
  for ACCOUNT_INDEX in {0..2}; do
    ACCOUNT_ID="$(cat keys/account-${ACCOUNT_INDEX}/account-id)"
    ./client.sh node-0 transfer \
      --private-key="/keys/faucet-account/account-private.pem" \
      --amount=3333333333 \
      --target-account="$ACCOUNT_ID" \
      --payment-amount=10000000
  done
}

# Transferring motes to each other
run_node_cycle() {
  NODE_INDEX="$1"
  ORIGINATOR_INDEX="$1"
  ORIGINATOR_PRIVATE_KEY="/keys/account-${ORIGINATOR_INDEX}/account-private.pem"
  RECIPIENT_INDEX=$(((ORIGINATOR_INDEX + 1) % 3))
  RECIPIENT_ID="$(cat keys/account-${RECIPIENT_INDEX}/account-id)"

  echo "Starting the backgroung node-${NODE_INDEX} deploy cycle"
  while true; do
    ./client.sh node-"$NODE_INDEX" transfer \
      --private-key="$ORIGINATOR_PRIVATE_KEY" \
      --amount=1 \
      --target-account="$RECIPIENT_ID" \
      --payment-amount=10000000
      sleep 5
  done
}

initialize_accounts

sleep 10

for i in {0..2}; do
  run_node_cycle "$i" &
done

# Waiting (endlessly or until ctrl-c/error/etc.) until background processes stop, see the link at the top
wait
