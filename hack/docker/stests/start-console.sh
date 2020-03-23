#!/usr/bin/env bash

set -e

# stests will register aliases that normally cannot be used in scripts.
shopt -s expand_aliases

# Activate the stests commands.
source $HOME/.bashrc

# Get rid of the pushd/popd decorations.
source $STESTS_PATH_SH/utils.sh
# Make sure we get rid of them in the interactive session too.
cat >> $HOME/.bashrc <<- EOM
	source $STESTS_PATH_SH/utils.sh
EOM

echo 1.  Register network + faucet key:
stests-set-network $NETWORK
stests-set-network-faucet-key $NETWORK $KEYS/faucet-account/account-private.pem

echo 2.  Register nodes + node bonding keys:

for NODE in $NODES; do
  echo Registering $NODE...
  n=$(echo $NODE | sed -r 's/node-([0-9])/\1/')
  i=$(( n + 1 ))
  stests-set-node $NETWORK:${i} ${NODE}:40401 full
  stests-set-node-bonding-key $NETWORK:${i} $KEYS/account-${n}/account-private.pem
done

echo 3.  Switching to interactive mode:

exec bash
